package dev.igorshahin.discovery

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.igorshahin.execution.TestRunScope
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import org.jetbrains.concurrency.AsyncPromise

class RunDiscoveryBarrierAsyncTest : BasePlatformTestCase() {
    fun testRunWaitsForIncrementalPublicationAndAnalyzerBeforeDeliveringNewModelOnce() {
        val path = "/project/test/a_test.dart"
        val scope = TestRunScope("file-a", ExplorerNodeKind.FILE, path)
        val old = ExplorerNode(ExplorerNodeKind.FILE, "old", id = "file-a")
        val current = old.copy(label = "current")
        val version = FileVersion(2, 1)
        var state = RunDiscoverySnapshot(DiscoveryCacheState(mapOf(path to
            CachedTestFile("test/a_test.dart", FileVersion(1, 1), null, emptySet()))), old, null)
        val requested = AsyncPromise<DiscoveryChanges>()
        val ready = AsyncPromise<ExplorerNode>()
        var callbacks = 0
        val barrier = RunDiscoveryBarrier(project, testRootDisposable, { state }, { version }) { changes ->
            state = state.copy(changesInFlight = changes)
            requested.setResult(changes)
        }
        barrier.ensureCurrent(scope) { result -> callbacks++; ready.setResult(result.getOrThrow()) }
        val changes = requireNotNull(PlatformTestUtil.waitForPromise(requested, 10_000))
        assertEquals(setOf(path), changes.paths)
        assertFalse(changes.rescan)
        assertEquals(0, callbacks)

        state = state.copy(cache = DiscoveryCacheState(mapOf(path to
            CachedTestFile("test/a_test.dart", version, null, emptySet(), awaitingAnalysis = true))), changesInFlight = null)
        barrier.changed()
        // Next publication is the settled analyzer result, carrying the new immutable model.
        state = state.copy(cache = state.cache.copy(files = state.cache.files.mapValues {
            it.value.copy(awaitingAnalysis = false)
        }), model = current)
        barrier.changed()
        assertSame(current, PlatformTestUtil.waitForPromise(ready, 10_000))
        barrier.changed()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(1, callbacks)
    }
}
