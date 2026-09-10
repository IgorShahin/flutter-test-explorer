package dev.igorshahin.execution

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.discovery.TestSourceStamp
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.settings.TestExplorerSettings
import io.flutter.FlutterUtils

/** All scopes share this adapter and the same project arguments. Multi-file runs are sequential
 * official runner sessions, not shell processes; the queue stops on failure or cancellation.
 */
class TestExecutionService @JvmOverloads constructor(private val project: Project, private val parent: Disposable,
                                                    private val refresher: TestModelRefresher? = null) {
    private val planner = TestExecutionPlanner()
    private val factory = TestConfigurationFactory(project)
    private val pending = ArrayDeque<Pair<ExplorerNode, RunnerAndConfigurationSettings>>()
    private var active: RunnerAndConfigurationSettings? = null
    private var activePath: String? = null
    private data class Request(val scope: TestRunScope, val excluded: Set<String>, val arguments: List<String>,
                               var model: ExplorerNode, val completedFiles: MutableSet<String> = mutableSetOf())
    private var request: Request? = null
    private var preparing = false
    private var disposed = false

    init {
        com.intellij.openapi.util.Disposer.register(parent) { disposed = true; pending.clear(); request = null }
        project.messageBus.connect(parent).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                if (active == null || env.runnerAndConfigurationSettings !== active) return
                ApplicationManager.getApplication().invokeLater {
                    val completedPath = activePath
                    active = null
                    activePath = null
                    if (disposed) return@invokeLater
                    if (!canContinueBatch(handler, exitCode)) {
                        if (pending.isNotEmpty()) notifyError("Test run stopped or failed. Remaining files were not started.")
                        pending.clear()
                        request = null
                    } else {
                        completedPath?.let { request?.completedFiles?.add(it) }
                        startNext()
                    }
                }
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (active == null || env.runnerAndConfigurationSettings !== active) return
                ApplicationManager.getApplication().invokeLater {
                    active = null
                    activePath = null
                    pending.clear()
                    request = null
                }
            }
        })
    }

    fun run(complete: ExplorerNode, selectedId: String) {
        if (request != null || active != null || preparing) {
            notifyError("A Test Explorer run is already active. Wait for it to finish or stop it in the Run window.")
            return
        }
        val settings = project.getService(TestExplorerSettings::class.java)
        val selected = TestVisibility.find(complete, selectedId)
        if (selected == null) { notifyError("The selected test no longer exists."); return }
        request = Request(TestRunScope.from(selected), settings.excludedNodeIds, settings.enabledArgumentValues, complete)
        prepareLatest()
    }

    private fun prepareLatest() {
        val current = request ?: return
        pending.clear()
        preparing = true
        val refresh = refresher
        if (refresh == null) prepare(current, current.model) else refresh.ensureCurrent(current.scope) { result ->
            if (disposed || request !== current) return@ensureCurrent
            result.fold(onSuccess = { prepare(current, it) }, onFailure = { fail(it) })
        }
    }

    private fun prepare(current: Request, model: ExplorerNode) {
        current.model = model
        // Re-resolve the stable ID in the CURRENT tree; never fall back to a parent/old offset.
        val plan = planner.plan(model, current.scope.selectedId, current.excluded)
        if (plan.error != null) { fail(IllegalStateException(plan.error)); return }
        val targets = plan.targets.filter { it.target.fileOrDirectoryPath !in current.completedFiles }
        ReadAction.nonBlocking<Result<List<Pair<ExplorerNode, RunnerAndConfigurationSettings>>>> {
            // Validate the ENTIRE batch before starting anything, avoiding partial accidental runs.
            try {
                Result.success(targets.map { execution ->
                    val node = execution.source
                    val file = checkedSource(node)
                    val psi = PsiManager.getInstance(project).findFile(file)
                        ?: throw SourceChanged()
                    val config = factory.create(execution.target, node.label,
                        FlutterUtils.isInFlutterProject(project, psi), current.arguments, execution.nameFilter)
                    config.configuration.checkConfiguration()
                    node to config
                })
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Expected validation failures are user-facing outcomes, not rejected promises
                // (NonBlockingReadAction reports those as plugin errors in the IDE).
                Result.failure(error)
            }
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(parent)
            // startNext saves the current document: use a write-safe modality, never "any".
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                preparing = false
                if (!disposed && request === current) {
                    result.fold(onSuccess = { configurations ->
                        pending.addAll(configurations)
                        startNext()
                    }, onFailure = { error ->
                        if (error is SourceChanged && refresher != null) prepareLatest() else fail(error)
                    })
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { error ->
                ApplicationManager.getApplication().invokeLater {
                    preparing = false
                    pending.clear()
                    if (!disposed && request === current) {
                        if (error is ProcessCanceledException || error is java.util.concurrent.CancellationException) prepareLatest()
                        else fail(error)
                    }
                }
            }
    }

    private fun checkedSource(node: ExplorerNode): com.intellij.openapi.vfs.VirtualFile {
        val location = requireNotNull(node.location)
        val file = LocalFileSystem.getInstance().findFileByPath(location.filePath)
            ?: throw SourceChanged()
        if (!file.isValid || (location.modificationStamp != null && TestSourceStamp.current(file) != location.modificationStamp))
            throw SourceChanged()
        return file
    }

    private fun startNext() {
        if (disposed) return
        if (pending.isEmpty()) { request = null; return }
        val (node, config) = pending.removeFirst()
        try {
            val file = checkedSource(node)
            // Native runners execute files on disk. Save only this current target's document,
            // as a normal Run action does; discovery itself never saves/rewrites source.
            val documents = FileDocumentManager.getInstance()
            documents.getCachedDocument(file)?.let { document ->
                documents.saveDocument(document)
                check(!documents.isDocumentUnsaved(document)) { "Cannot save the current test source. Nothing was started." }
            }
            checkedSource(node)
        } catch (error: SourceChanged) {
            pending.clear()
            if (refresher != null) prepareLatest() else fail(error)
            return
        } catch (error: Exception) {
            fail(error)
            return
        }
        active = config
        activePath = node.location?.filePath
        RunManager.getInstance(project).apply {
            setTemporaryConfiguration(config)
            selectedConfiguration = config
        }
        ProgramRunnerUtil.executeConfiguration(config, DefaultRunExecutor.getRunExecutorInstance())
    }

    private class SourceChanged : IllegalStateException("The test source changed while preparing its run.")

    private fun fail(error: Throwable) {
        preparing = false
        pending.clear()
        request = null
        if (!disposed) notifyError(error.message ?: "The IDE cannot run this test configuration.")
    }

    private fun notifyError(content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Flutter Test Explorer")
            .createNotification("Unable to run tests", content, NotificationType.WARNING).notify(project)
    }

    internal companion object {
        fun canContinueBatch(handler: ProcessHandler, exitCode: Int): Boolean =
            // Flutter may destroy its process on SUCCESS (willBeDestroyed=true, exit=0).
            // IDEA's native Stop path sets TERMINATION_REQUESTED explicitly; destruction alone
            // is not evidence of cancellation. Decide only after this file has terminated.
            exitCode == 0 && handler.getUserData(ProcessHandler.TERMINATION_REQUESTED) != true
    }
}
