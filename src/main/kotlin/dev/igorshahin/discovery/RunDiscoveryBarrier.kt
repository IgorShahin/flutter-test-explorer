package dev.igorshahin.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import dev.igorshahin.execution.TestModelRefresher
import dev.igorshahin.execution.TestRunScope
import dev.igorshahin.model.ExplorerNode
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.CancellationException

internal data class RunDiscoverySnapshot(val cache: DiscoveryCacheState, val model: ExplorerNode?,
                                         val changesInFlight: DiscoveryChanges?)
internal data class RunDiscoveryDecision(val refresh: Set<String> = emptySet(), val ready: ExplorerNode? = null)

/** Coordinates the existing incremental discovery loop with Run. No Swing, rescans, polling,
 * source execution or PSI retention. Source/analyzer changes and published batches wake it. */
internal class RunDiscoveryBarrier(
    private val project: Project,
    private val parent: Disposable,
    private val snapshot: () -> RunDiscoverySnapshot,
    private val version: (String) -> FileVersion,
    private val refresh: (DiscoveryChanges) -> Unit,
) : TestModelRefresher {
    private data class Waiting(val scope: TestRunScope, val ready: (Result<ExplorerNode>) -> Unit)
    private var waiting: Waiting? = null
    private var promise: CancellablePromise<Result<RunDiscoveryDecision>>? = null
    private var generation = 0L
    private var disposed = false
    private val timeout = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    init { Disposer.register(parent) { disposed = true; waiting = null; promise?.cancel() } }

    override fun ensureCurrent(scope: TestRunScope, ready: (Result<ExplorerNode>) -> Unit) {
        check(waiting == null) { "A discovery/run request is already pending" }
        if (disposed) return
        waiting = Waiting(scope, ready)
        timeout.addRequest({ finish(Result.failure(IllegalStateException(
            "Dart/Flutter analysis is still pending. Check the SDK/analyzer or finish indexing, then try Run again. Nothing was started."))) }, 30_000)
        changed()
    }

    fun changed() {
        generation++
        checkCurrent()
    }

    private fun checkCurrent() {
        val request = waiting ?: return
        if (disposed || promise != null) return
        val captured = snapshot()
        val token = generation
        promise = ReadAction.nonBlocking<Result<RunDiscoveryDecision>> {
            try { Result.success(assess(request.scope, captured, version)) }
            catch (cancelled: ProcessCanceledException) { throw cancelled }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }.inSmartMode(project).withDocumentsCommitted(project).expireWith(parent)
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                if (waiting !== request || disposed) return@finishOnUiThread
                promise = null
                if (token != generation) { checkCurrent(); return@finishOnUiThread }
                result.fold(onSuccess = { decision ->
                    when {
                        decision.refresh.isNotEmpty() -> refresh(DiscoveryChanges(paths = decision.refresh))
                        decision.ready != null -> finish(Result.success(decision.ready))
                        // Await the next document/analyzer/discovery event, not another polling read.
                    }
                }, onFailure = { finish(Result.failure(it)) })
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    fun failed(error: Throwable) { if (waiting != null) finish(Result.failure(error)) }

    private fun finish(result: Result<ExplorerNode>) {
        val request = waiting ?: return
        waiting = null
        timeout.cancelAllRequests()
        promise?.cancel()
        promise = null
        if (!disposed) request.ready(result)
    }

    companion object {
        internal fun assess(scope: TestRunScope, snapshot: RunDiscoverySnapshot,
                            version: (String) -> FileVersion): RunDiscoveryDecision {
            val changes = snapshot.changesInFlight
            val dependencyPending = changes?.let { changed ->
                changed.paths.any { snapshot.cache.dependents[it].orEmpty().any(scope::includes) } ||
                    (changed.subtrees.isNotEmpty() && snapshot.cache.dependents.any { (dependency, files) ->
                        changed.subtrees.any { DiscoveryCache.beneath(dependency, it) } && files.any(scope::includes)
                    })
            } == true
            if (snapshot.model == null || dependencyPending || (changes != null && affects(scope, changes))) return RunDiscoveryDecision()
            val files = snapshot.cache.files.filterKeys(scope::includes)
            val stale = files.filter { (path, cached) -> version(path) != cached.version }.keys
            if (stale.isNotEmpty()) return RunDiscoveryDecision(refresh = stale)
            if (files.values.any { it.awaitingAnalysis }) return RunDiscoveryDecision()
            return RunDiscoveryDecision(ready = snapshot.model)
        }

        private fun affects(scope: TestRunScope, changes: DiscoveryChanges): Boolean =
            changes.rescan || changes.invalidateAll || (changes.paths + changes.outlines).any(scope::includes) ||
                changes.subtrees.any { scope.includes(it) || scope.path?.let { path -> DiscoveryCache.beneath(path, it) } == true }
    }
}
