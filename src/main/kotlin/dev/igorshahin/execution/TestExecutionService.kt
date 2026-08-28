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
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.settings.TestExplorerSettings
import io.flutter.FlutterUtils

/** All scopes share this adapter and the same project arguments. Multi-file runs are sequential
 * official runner sessions, not shell processes; the queue stops on failure or cancellation.
 */
class TestExecutionService(private val project: Project, private val parent: Disposable) {
    private val planner = TestExecutionPlanner()
    private val factory = TestConfigurationFactory(project)
    private val pending = ArrayDeque<Pair<ExplorerNode, RunnerAndConfigurationSettings>>()
    private var active: RunnerAndConfigurationSettings? = null
    private var preparing = false
    private var disposed = false

    init {
        com.intellij.openapi.util.Disposer.register(parent) { disposed = true; pending.clear() }
        project.messageBus.connect(parent).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (active == null || env.runnerAndConfigurationSettings !== active) return
                handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                    override fun processWillTerminate(event: com.intellij.execution.process.ProcessEvent, willBeDestroyed: Boolean) {
                        if (willBeDestroyed) ApplicationManager.getApplication().invokeLater { pending.clear() }
                    }
                })
            }
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                if (active == null || env.runnerAndConfigurationSettings !== active) return
                ApplicationManager.getApplication().invokeLater {
                    active = null
                    if (disposed) return@invokeLater
                    if (exitCode != 0) {
                        if (pending.isNotEmpty()) notifyError("Test run stopped or failed. Remaining files were not started.")
                        pending.clear()
                    } else startNext()
                }
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (active == null || env.runnerAndConfigurationSettings !== active) return
                ApplicationManager.getApplication().invokeLater {
                    active = null
                    pending.clear()
                }
            }
        })
    }

    fun run(complete: ExplorerNode, selectedId: String) {
        if (active != null || preparing) {
            notifyError("A Test Explorer run is already active. Wait for it to finish or stop it in the Run window.")
            return
        }
        val settings = project.getService(TestExplorerSettings::class.java)
        val plan = planner.plan(complete, selectedId, settings.excludedNodeIds)
        if (plan.error != null) { notifyError(plan.error); return }
        val arguments = settings.globalArguments
        preparing = true
        ReadAction.nonBlocking<List<Pair<ExplorerNode, RunnerAndConfigurationSettings>>> {
            // Validate the ENTIRE batch before starting anything, avoiding partial accidental runs.
            plan.targets.map { node ->
                val file = checkedSource(node)
                val psi = PsiManager.getInstance(project).findFile(file)
                    ?: error("Test source no longer exists. Refresh the explorer.")
                val config = factory.create(requireNotNull(node.runTarget), node.label,
                    FlutterUtils.isInFlutterProject(project, psi), arguments)
                config.configuration.checkConfiguration()
                node to config
            }
        }
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { configurations ->
                preparing = false
                if (!disposed) {
                    pending.addAll(configurations)
                    startNext()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { error ->
                if (error is ProcessCanceledException || error is java.util.concurrent.CancellationException) return@onError
                ApplicationManager.getApplication().invokeLater {
                    preparing = false
                    pending.clear()
                    if (!disposed) notifyError(error.message ?: "The IDE cannot run this test configuration.")
                }
            }
    }

    private fun checkedSource(node: ExplorerNode): com.intellij.openapi.vfs.VirtualFile {
        val location = requireNotNull(node.location)
        val file = LocalFileSystem.getInstance().findFileByPath(location.filePath)
            ?: error("Test source no longer exists. Refresh the explorer.")
        val psi = PsiManager.getInstance(project).findFile(file)
        check(file.isValid && (location.modificationStamp == null || psi?.modificationStamp == location.modificationStamp)) {
            "Test source changed since discovery. Refresh the explorer before running."
        }
        return file
    }

    private fun startNext() {
        if (disposed || pending.isEmpty()) return
        val (node, config) = pending.removeFirst()
        try {
            checkedSource(node)
        } catch (error: IllegalStateException) {
            pending.clear()
            notifyError(error.message.orEmpty())
            return
        }
        active = config
        RunManager.getInstance(project).apply {
            setTemporaryConfiguration(config)
            selectedConfiguration = config
        }
        ProgramRunnerUtil.executeConfiguration(config, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun notifyError(content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Flutter Test Explorer")
            .createNotification("Unable to run tests", content, NotificationType.WARNING).notify(project)
    }
}
