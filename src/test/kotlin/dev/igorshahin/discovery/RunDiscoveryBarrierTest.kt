package dev.igorshahin.discovery

import dev.igorshahin.execution.TestExecutionPlanner
import dev.igorshahin.execution.TestRunScope
import dev.igorshahin.model.*
import org.junit.Assert.*
import org.junit.Test

class RunDiscoveryBarrierTest {
    private val a = "/project/test/a_test.dart"
    private val b = "/project/test/b_test.dart"
    private val old = FileVersion(1, 1)
    private fun file(path: String, offset: Int = 10, name: String = "case") = DartTestFile(
        path.removePrefix("/project/"), SourceLocation(path, 0, 1),
        listOf(DartTestItem(DartTestKind.TEST, name, SourceLocation(path, offset, 1))))
    private val model = TestExplorerTreeBuilder().build(listOf(file(a), file(b)))
    private val cache = DiscoveryCacheState(files = listOf(a, b).associateWith {
        CachedTestFile(it.removePrefix("/project/"), old, file(it), emptySet())
    })
    private val fileScope = TestRunScope("file-a", ExplorerNodeKind.FILE, a)
    private val rootScope = TestRunScope(model.id, ExplorerNodeKind.ROOT, "/project")

    @Test fun `clean Run reads versions but requests no discovery`() {
        val read = mutableListOf<String>()
        val decision = RunDiscoveryBarrier.assess(fileScope, RunDiscoverySnapshot(cache, model, null)) {
            read += it; old
        }
        assertSame(model, decision.ready)
        assertTrue(decision.refresh.isEmpty())
        assertEquals(listOf(a), read)
    }

    @Test fun `immediate Run detects changed document before its debounced discovery`() {
        val decision = RunDiscoveryBarrier.assess(fileScope, RunDiscoverySnapshot(cache, model, null)) {
            if (it == a) FileVersion(2, 1) else old
        }
        assertEquals(setOf(a), decision.refresh)
        assertNull(decision.ready)
        val changes = DiscoveryChanges(paths = decision.refresh)
        assertFalse(changes.rescan)
        assertFalse(changes.invalidateAll)
    }

    @Test fun `queued or running edits block stale cached nodes until publication`() {
        listOf(DiscoveryChanges(paths = setOf(a)), DiscoveryChanges(outlines = setOf(a)),
            DiscoveryChanges(subtrees = setOf("/project/test"))).forEach { changes ->
            val decision = RunDiscoveryBarrier.assess(fileScope, RunDiscoverySnapshot(cache, model, changes)) { old }
            assertNull(decision.ready)
            assertTrue(decision.refresh.isEmpty()) // reuse the queued transaction, do not duplicate it
        }
    }

    @Test fun `waiting analyzer cannot release last good but stale branch`() {
        val waiting = cache.copy(files = cache.files + (a to cache.files.getValue(a).copy(
            version = FileVersion(2, 1), awaitingAnalysis = true)))
        val snapshot = RunDiscoverySnapshot(waiting, model, null)
        val pending = RunDiscoveryBarrier.assess(fileScope, snapshot) { waiting.files.getValue(it).version }
        assertNull(pending.ready)
        assertTrue(pending.refresh.isEmpty())
        val arrived = RunDiscoveryBarrier.assess(fileScope, snapshot) { FileVersion(2, 2) }
        assertEquals(setOf(a), arrived.refresh)
        assertNull(arrived.ready)
    }

    @Test fun `unrelated discovery does not block a single file but root waits for its scope`() {
        val snapshot = RunDiscoverySnapshot(cache, model, DiscoveryChanges(paths = setOf(b)))
        assertSame(model, RunDiscoveryBarrier.assess(fileScope, snapshot) { old }.ready)
        assertNull(RunDiscoveryBarrier.assess(rootScope, snapshot) { old }.ready)
        val changed = RunDiscoveryBarrier.assess(rootScope, snapshot.copy(changesInFlight = null)) {
            if (it == b) FileVersion(2, 1) else old
        }
        assertEquals(setOf(b), changed.refresh)
    }

    @Test fun `published model provides new offsets and removed IDs never fall back to broad run`() {
        val previousLeaf = model.children.single().children.first().children.single()
        val updated = TestExplorerTreeBuilder().build(listOf(file(a, offset = 100), file(b)))
        val fresh = RunDiscoveryBarrier.assess(fileScope, RunDiscoverySnapshot(cache, updated, null)) { old }.ready!!
        val plan = TestExecutionPlanner().plan(fresh, previousLeaf.id, emptySet())
        assertEquals(100, plan.targets.single().source.location!!.offset)
        val renamed = TestExplorerTreeBuilder().build(listOf(file(a, name = "renamed"), file(b)))
        val gone = TestExecutionPlanner().plan(renamed, previousLeaf.id, emptySet())
        assertNotNull(gone.error)
        assertFalse(gone.error!!.contains("Refresh"))
        assertTrue(gone.targets.isEmpty())
    }

    @Test fun `pending Dart dependency invalidation also holds the affected file run`() {
        val state = cache.copy(dependents = mapOf("/project/lib/helper.dart" to setOf(a)))
        val snapshot = RunDiscoverySnapshot(state, model, DiscoveryChanges(paths = setOf("/project/lib/helper.dart")))
        assertNull(RunDiscoveryBarrier.assess(fileScope, snapshot) { old }.ready)
        assertSame(model, RunDiscoveryBarrier.assess(fileScope.copy(path = b), snapshot) { old }.ready)
    }
}
