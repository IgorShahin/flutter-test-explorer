package dev.igorshahin.discovery

import dev.igorshahin.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCacheTest {
    private class Backend : DiscoveryBackend {
        val stamps = mutableMapOf<String, Long>()
        val revisions = mutableMapOf<String, Long>()
        val imports = mutableMapOf<String, Set<String>>()
        val analyzed = mutableListOf<String>()
        var empty = false
        var pending = false
        var canceled = false
        override fun version(path: String) = FileVersion(stamps[path] ?: 1, revisions[path] ?: 0)
        override fun discover(path: String, relativePath: String): DartTestFile? {
            analyzed += path
            return if (empty) null else DartTestFile(relativePath, SourceLocation(path, 0),
                listOf(DartTestItem(DartTestKind.TEST, "test", SourceLocation(path, 10))))
        }
        override fun dependencies(path: String) = imports[path].orEmpty()
        override fun awaitingAnalysis(path: String) = pending
        override fun checkCanceled() { if (canceled) throw IllegalStateException("cancel") }
    }
    private val backend = Backend()
    private val cache = DiscoveryCache(backend)
    private val candidates = (1..200).associate { "/p/test/${it}_test.dart" to "test/${it}_test.dart" }

    @Test fun `one file edit analyzes one and duplicate save is a hit`() {
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        assertEquals(200, first.metrics.analyzed)
        val path = candidates.keys.first()
        backend.stamps[path] = 2
        val second = cache.update(first.state, candidates, DiscoveryChanges(paths = setOf(path)))
        assertEquals(1, second.metrics.analyzed)
        assertEquals(1, second.replacements.size)
        assertSame(first.state.files[candidates.keys.last()], second.state.files[candidates.keys.last()])
        val saved = cache.update(second.state, candidates, DiscoveryChanges(paths = setOf(path)))
        assertEquals(0, saved.metrics.analyzed)
        assertEquals(1, saved.metrics.cacheHits)
        assertTrue(saved.replacements.isEmpty())
        assertEquals(1L, saved.metrics.fullRefreshes)
        assertEquals(2L, saved.metrics.incrementalRefreshes)
    }

    @Test fun `negative result cached and outline revision invalidates it without source change`() {
        backend.empty = true
        backend.pending = true
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        assertEquals(200, first.state.files.size)
        assertTrue(first.state.files.values.all { it.awaitingAnalysis })
        val path = candidates.keys.first()
        val repeated = cache.update(first.state, candidates, DiscoveryChanges(outlines = setOf(path)))
        assertEquals(0, repeated.metrics.analyzed)
        backend.empty = false
        backend.pending = false
        backend.revisions[path] = 1
        val outlined = cache.update(repeated.state, candidates, DiscoveryChanges(outlines = setOf(path)))
        assertEquals(1, outlined.metrics.analyzed)
        assertNotNull(outlined.state.files[path]!!.result)
        assertFalse(outlined.state.files[path]!!.awaitingAnalysis)
    }

    @Test fun `helper changes invalidate only dependent tests never helper PSI`() {
        val affected = candidates.keys.take(2)
        affected.forEach { backend.imports[it] = setOf("/p/lib/helper.dart", "/p/lib/transitive.dart") }
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        val unrelated = cache.update(first.state, candidates, DiscoveryChanges(paths = setOf("/p/lib/unrelated.dart")))
        assertEquals(0, unrelated.metrics.analyzed)
        val updated = cache.update(unrelated.state, candidates, DiscoveryChanges(paths = setOf("/p/lib/transitive.dart")))
        assertEquals(2, updated.metrics.analyzed)
        assertEquals(affected.toSet(), updated.replacements.values.map { it!!.location.filePath }.toSet())
        assertTrue(backend.analyzed.all { it.endsWith("_test.dart") })
    }

    @Test fun `create delete rename and directory move maintain entries and prune dependencies`() {
        val old = candidates.keys.first()
        backend.imports[old] = setOf("/p/lib/helper.dart")
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        val renamed = "/p/integration_test/moved_test.dart"
        val added = candidates - old + (renamed to "integration_test/moved_test.dart")
        val moved = cache.update(first.state, added, DiscoveryChanges(paths = setOf(old, renamed)))
        assertEquals(1, moved.metrics.analyzed)
        assertFalse(moved.state.files.containsKey(old))
        assertFalse(moved.state.dependents.containsKey("/p/lib/helper.dart"))
        assertTrue(moved.replacements.containsKey(candidates[old]))
        assertNull(moved.replacements[candidates[old]])
        val deleted = cache.update(moved.state, emptyMap(), DiscoveryChanges(subtrees = setOf("/p")))
        assertTrue(deleted.state.files.isEmpty())
        assertEquals(200, deleted.replacements.size)
        assertEquals(0, deleted.metrics.analyzed)
    }

    @Test fun `index reconciliation keeps cached PSI but explicit refresh invalidates all`() {
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        val roots = cache.update(first.state, candidates, DiscoveryChanges(rescan = true))
        assertEquals(200, roots.metrics.cacheHits)
        assertEquals(0, roots.metrics.analyzed)
        val manual = cache.update(roots.state, candidates, DiscoveryChanges(rescan = true, invalidateAll = true))
        assertEquals(200, manual.metrics.analyzed)
    }

    @Test fun `canceled transaction leaves previous snapshot untouched and changes coalesce`() {
        val first = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true))
        backend.canceled = true
        try {
            cache.update(first.state, candidates, DiscoveryChanges(rescan = true))
            fail("must cancel")
        } catch (_: IllegalStateException) { }
        assertEquals(200, first.state.files.size)
        assertEquals(1L, first.state.fullRefreshes)
        val merged = DiscoveryChanges(paths = setOf("a")).merge(DiscoveryChanges(paths = setOf("a", "b"), outlines = setOf("c")))
        assertEquals(setOf("a", "b"), merged.paths)
        assertEquals(setOf("c"), merged.outlines)
    }
}
