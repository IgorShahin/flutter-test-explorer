package dev.igorshahin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import dev.igorshahin.MyMessageBundle
import dev.igorshahin.discovery.*
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.execution.TestExecutionService
import dev.igorshahin.execution.TestModelRefresher
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import dev.igorshahin.model.SourceLocation
import dev.igorshahin.model.TestExplorerFilter
import dev.igorshahin.model.TestModelPatcher
import dev.igorshahin.model.TestNodeId
import dev.igorshahin.navigation.TestSourceNavigator
import dev.igorshahin.settings.TestExplorerConfigurable
import dev.igorshahin.settings.TestExplorerSettings
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel
import javax.swing.event.DocumentEvent
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.CancellationException

class TestExplorerPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val outlines = FlutterTestOutlineIndex(project) { path ->
        requestDiscovery(if (path == null) DiscoveryChanges(rescan = true, invalidateAll = true)
            else DiscoveryChanges(outlines = setOf(path)))
    }
    private val discovery = DartTestDiscovery(project, IntelliJTestRunnabilityValidator(project, outlines))
    private val backend = IndexedDiscoveryBackend(project, discovery, outlines)
    private val cache = DiscoveryCache(backend)
    private val patcher = TestModelPatcher()
    private var cacheState = DiscoveryCacheState()
    private val treeFilter = TestExplorerFilter()
    private val navigator = TestSourceNavigator(project)
    private val execution = TestExecutionService(project, this,
        TestModelRefresher { scope, ready -> runBarrier.ensureCurrent(scope, ready) })
    private val settings = project.getService(TestExplorerSettings::class.java)
    private val projectLabel = project.basePath
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?: project.name
    private val tree = InlineRunTree({ !disposed && completeModel != null }, ::runNode).apply {
        isRootVisible = true
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val treeUpdater = TestTreeUpdater(tree)
    private val refreshQueue = MergingUpdateQueue(
        "Flutter Test Explorer refresh",
        REFRESH_DELAY_MS,
        true,
        this,
        this,
    ).apply { setRestartTimerOnAdd(true) }
    private val filterField = SearchTextField(false).apply {
        textEditor.emptyText.text = MyMessageBundle.message("toolwindow.tests.filter.placeholder")
        textEditor.accessibleContext.accessibleName =
            MyMessageBundle.message("toolwindow.tests.filter.accessible")
    }
    private val filterQueue = MergingUpdateQueue("Flutter Test Explorer filter", 120, true, this, this)
    private var discoveryPromise: CancellablePromise<DiscoveryBatch>? = null
    private var filterPromise: CancellablePromise<ExplorerNode>? = null
    private var pendingChanges: DiscoveryChanges? = null
    private var runningChanges: DiscoveryChanges? = null
    private var completeModel: ExplorerNode? = null
    private var discoveryGeneration = 0L
    private var filterGeneration = 0L
    @Volatile
    private var disposed = false
    private val runBarrier = RunDiscoveryBarrier(project, this, {
        RunDiscoverySnapshot(cacheState, completeModel,
            pendingChanges?.let { pending -> runningChanges?.merge(pending) ?: pending } ?: runningChanges)
    }, backend::version, ::requestDiscovery)

    init {
        Disposer.register(this, outlines)
        TreeSpeedSearch.installOn(tree, true) { path ->
            ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode)?.label
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1) return
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode)
                    ?.location?.let(navigator::navigate)
            }
        })
        filterField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                filterQueue.queue(Update.create("filter") { showFilteredModel() })
            }
        })

        val runAction = action("Run Selected", AllIcons.Actions.Execute, { selectedNode()?.runnable == true }, true) {
            runSelectedNode()
        }
        val actions = DefaultActionGroup(
            action("Run All in Visible Scope", AllIcons.Actions.RunAll, { completeModel != null }, true) {
                completeModel?.let { runNode(it.id) }
            },
            runAction,
            action("Refresh Tests", AllIcons.Actions.Refresh) {
                requestDiscovery(DiscoveryChanges(rescan = true, invalidateAll = true))
            },
            action("Test Visibility", AllIcons.General.Filter, { completeModel != null }) {
                completeModel?.let { model ->
                    val dialog = TestVisibilityDialog(project, model, settings.excludedNodeIds)
                    if (dialog.showAndGet()) {
                        settings.excludedNodeIds = dialog.excludedIds()
                        showFilteredModel()
                    }
                }
            },
            action("Global Run Arguments", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TestExplorerConfigurable::class.java)
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("FlutterTestExplorer.Toolbar", actions, true)
        toolbar.targetComponent = tree
        installRunInteractions(runAction)
        setToolbar(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(2)
            add(toolbar.component, BorderLayout.NORTH)
            add(filterField, BorderLayout.SOUTH)
        })
        setContent(ScrollPaneFactory.createScrollPane(tree, true))

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    project.basePath?.let { base -> TestDiscoveryEvents.vfs(events, base)?.let(::requestDiscovery) }
                }
            },
        )
        project.messageBus.connect(this).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                requestDiscovery(DiscoveryChanges(rescan = true, invalidateAll = true))
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                val base = project.basePath ?: return
                if (!DiscoveryCache.beneath(path, base)) return
                when {
                    TestDiscoveryEvents.isConfiguration(path) ->
                        requestDiscovery(DiscoveryChanges(rescan = true, invalidateAll = true))
                    path.endsWith(".dart") -> requestDiscovery(DiscoveryChanges(paths = setOf(path)))
                }
            }
        }, this)
        showMessage(MyMessageBundle.message("toolwindow.tests.loading"))
        requestDiscovery(DiscoveryChanges(rescan = true))
    }

    fun preferredFocusComponent(): JComponent = tree

    private fun requestDiscovery(changes: DiscoveryChanges) {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        // Document events already run on EDT. Record dirtiness before a same-turn Run can fire.
        if (application.isDispatchThread) enqueueDiscovery(changes)
        else application.invokeLater({ if (!disposed) enqueueDiscovery(changes) }, ModalityState.any())
    }

    private fun enqueueDiscovery(changes: DiscoveryChanges) {
        pendingChanges = pendingChanges?.merge(changes) ?: changes
        runBarrier.changed()
        refreshQueue.queue(Update.create(REFRESH_UPDATE_ID, ::refreshNow))
    }

    private fun refreshNow() {
        if (disposed || discoveryPromise != null) return
        val changes = pendingChanges ?: return
        pendingChanges = null
        runningChanges = changes
        val previous = cacheState
        val previousModel = completeModel ?: ExplorerNode(ExplorerNodeKind.ROOT, projectLabel,
            project.basePath?.let { SourceLocation(it, 0) }, id = TestNodeId.ROOT)
        val generation = ++discoveryGeneration
        tree.setPaintBusy(true)

        discoveryPromise = ReadAction.nonBlocking<DiscoveryBatch> {
            val started = System.nanoTime()
            val candidates = backend.candidates(previous, changes)
            val update = cache.update(previous, candidates, changes)
            val model = patcher.replace(previousModel, update.replacements)
            DiscoveryBatch(update, model, System.nanoTime() - started)
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { batch ->
                if (generation != discoveryGeneration) return@finishOnUiThread
                discoveryPromise = null
                runningChanges = null
                cacheState = batch.update.state
                LOG.debug("Test Explorer discovery: ${batch.update.metrics}, totalMs=${batch.elapsedNanos / 1_000_000.0}")
                completeModel = batch.model
                showFilteredModel()
                runBarrier.changed()
                tree.setPaintBusy(false)
                if (pendingChanges != null) refreshQueue.queue(Update.create(REFRESH_UPDATE_ID, ::refreshNow))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { error -> handleDiscoveryError(generation, changes, error) }
    }

    private fun handleDiscoveryError(generation: Long, changes: DiscoveryChanges, error: Throwable) {
        val cancelled = error is ProcessCanceledException || error is CancellationException
        if (!cancelled) LOG.warn("Dart/Flutter test discovery failed", error)
        ApplicationManager.getApplication().invokeLater({
            if (generation != discoveryGeneration || disposed) return@invokeLater
            discoveryPromise = null
            runningChanges = null
            tree.setPaintBusy(false)
            // Keep the last good model, especially during indexing or transient analysis failures.
            if (completeModel == null) showMessage(
                MyMessageBundle.message(
                    "toolwindow.tests.error",
                    error.message ?: error.javaClass.simpleName,
                ),
            )
            // Retry this transaction on the next external event/manual refresh, not in a tight loop.
            pendingChanges = pendingChanges?.merge(changes) ?: changes
            if (cancelled) refreshQueue.queue(Update.create(REFRESH_UPDATE_ID, ::refreshNow))
            else runBarrier.failed(error)
        }, ModalityState.any())
    }

    private fun showFilteredModel() {
        val model = completeModel ?: return
        val excluded = settings.excludedNodeIds.toSet()
        val query = filterField.text
        val pendingFiles = cacheState.files.values.count { it.awaitingAnalysis }
        val generation = ++filterGeneration
        filterPromise?.cancel()
        filterPromise = ReadAction.nonBlocking<ExplorerNode> {
            val started = System.nanoTime()
            val visibleScope = TestVisibility.apply(model, excluded)
            val filtered = treeFilter.apply(visibleScope, query)
            val displayed = if (pendingFiles > 0) {
                filtered.copy(children = filtered.children + ExplorerNode(ExplorerNodeKind.MESSAGE,
                    MyMessageBundle.message("toolwindow.tests.analysis.waiting", pendingFiles), id = "message:analysis"))
            } else if (filtered.children.isEmpty()) {
                filtered.copy(
                    children = listOf(
                        ExplorerNode(
                            ExplorerNodeKind.MESSAGE,
                            MyMessageBundle.message(if (model.children.isEmpty()) "toolwindow.tests.empty"
                                else "toolwindow.tests.filter.empty"), id = "message:empty",
                        ),
                    ),
                )
            } else filtered
            LOG.debug("Test Explorer filtering ms=${(System.nanoTime() - started) / 1_000_000.0}")
            displayed
        }
            .expireWith(this)
            .coalesceBy(this, "filter")
            .finishOnUiThread(ModalityState.any()) { displayed ->
                if (generation == filterGeneration && !disposed) {
                    val started = System.nanoTime()
                    treeUpdater.apply(displayed, expandSearch = query.isNotBlank())
                    LOG.debug("Test Explorer UI: visited=${treeUpdater.lastVisitedNodes}, ms=${(System.nanoTime() - started) / 1_000_000.0}")
                    filterPromise = null
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showMessage(message: String) {
        val root = ExplorerNode(
            kind = ExplorerNodeKind.ROOT,
            label = projectLabel,
            id = TestNodeId.ROOT,
            children = listOf(ExplorerNode(ExplorerNodeKind.MESSAGE, message, id = "message:status")),
        )
        treeUpdater.apply(root)
    }

    private fun selectedNode(): ExplorerNode? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode

    private fun installRunInteractions(runAction: AnAction) {
        tree.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
            RUN_ACTION_KEY,
        )
        tree.actionMap.put(RUN_ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = runSelectedNode()
        })
        val popup = ActionManager.getInstance()
            .createActionPopupMenu("FlutterTestExplorer.Popup", DefaultActionGroup(runAction))
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = showPopup(event)

            override fun mouseReleased(event: MouseEvent) = showPopup(event)

            private fun showPopup(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                tree.getPathForLocation(event.x, event.y)?.let { tree.selectionPath = it }
                popup.component.show(tree, event.x, event.y)
            }
        })
    }

    private fun runSelectedNode() {
        val selected = selectedNode()?.takeIf { it.runnable } ?: return
        runNode(selected.id)
    }

    private fun runNode(id: String) {
        if (disposed) return
        completeModel?.let { execution.run(it, id) }
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true },
                       allowDuringDiscovery: Boolean = false,
                       perform: () -> Unit): AnAction = object : DumbAwareAction(text, text, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !disposed && (allowDuringDiscovery || discoveryPromise == null) && enabled()
        }
        override fun actionPerformed(event: AnActionEvent) = perform()
    }

    override fun dispose() {
        disposed = true
        discoveryPromise?.cancel()
        discoveryPromise = null
        filterPromise?.cancel()
        filterPromise = null
        javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree)
    }

    private data class DiscoveryBatch(val update: DiscoveryCacheUpdate, val model: ExplorerNode, val elapsedNanos: Long)

    private companion object {
        val LOG = Logger.getInstance(TestExplorerPanel::class.java)
        const val REFRESH_DELAY_MS = 350
        const val REFRESH_UPDATE_ID = "discover-tests"
        const val RUN_ACTION_KEY = "run-selected-test"
    }
}
