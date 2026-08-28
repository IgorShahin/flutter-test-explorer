package dev.igorshahin.discovery

import dev.igorshahin.model.DartTestFile

/** Immutable transactions: cancelled/restarted non-blocking reads never publish a half-filled cache.
 * Null results are cached too (no outline yet, broken PSI, or no runnable tests). No PSI is retained. */
internal data class FileVersion(val sourceStamp: Long, val outlineRevision: Long)
internal data class CachedTestFile(val relativePath: String, val version: FileVersion,
                                   val result: DartTestFile?, val dependencies: Set<String>,
                                   val awaitingAnalysis: Boolean = false)
internal data class DiscoveryCacheState(
    val files: Map<String, CachedTestFile> = emptyMap(),
    val dependents: Map<String, Set<String>> = emptyMap(),
    val fullRefreshes: Long = 0,
    val incrementalRefreshes: Long = 0,
)
internal data class DiscoveryChanges(
    val paths: Set<String> = emptySet(),
    val subtrees: Set<String> = emptySet(),
    val outlines: Set<String> = emptySet(),
    val rescan: Boolean = false,
    val invalidateAll: Boolean = false,
) {
    fun merge(other: DiscoveryChanges) = DiscoveryChanges(paths + other.paths, subtrees + other.subtrees,
        outlines + other.outlines, rescan || other.rescan, invalidateAll || other.invalidateAll)
}
internal data class DiscoveryMetrics(val candidates: Int, val analyzed: Int, val cacheHits: Int,
                                     val invalidated: Int, val elapsedNanos: Long,
                                     val fullRefreshes: Long, val incrementalRefreshes: Long)
internal data class DiscoveryCacheUpdate(val state: DiscoveryCacheState,
                                         val replacements: Map<String, DartTestFile?>,
                                         val metrics: DiscoveryMetrics)

internal interface DiscoveryBackend {
    fun version(path: String): FileVersion
    fun discover(path: String, relativePath: String): DartTestFile?
    fun dependencies(path: String): Set<String>
    fun awaitingAnalysis(path: String): Boolean = false
    fun checkCanceled() {}
}

internal class DiscoveryCache(private val backend: DiscoveryBackend) {
    fun update(previous: DiscoveryCacheState, candidates: Map<String, String>, changes: DiscoveryChanges): DiscoveryCacheUpdate {
        val started = System.nanoTime()
        val files = previous.files.toMutableMap()
        val dependents = previous.dependents.toMutableMap()
        val replacements = linkedMapOf<String, DartTestFile?>()
        val changedDependencies = changes.paths.flatMap { previous.dependents[it].orEmpty() }.toMutableSet()
        if (changes.subtrees.isNotEmpty()) previous.dependents.forEach { (path, tests) ->
            if (changes.subtrees.any { beneath(path, it) }) changedDependencies += tests
        }
        val affected = if (changes.rescan || changes.invalidateAll) candidates.keys else buildSet {
            addAll(changes.paths.filter { it in candidates })
            addAll(changes.outlines.filter { it in candidates })
            addAll(changedDependencies)
            addAll(candidates.keys - previous.files.keys)
            if (changes.subtrees.isNotEmpty()) addAll(candidates.keys.filter { path -> changes.subtrees.any { beneath(path, it) } })
        }
        fun unlink(path: String, entry: CachedTestFile) {
            entry.dependencies.forEach { dependency ->
                val remaining = dependents[dependency].orEmpty() - path
                if (remaining.isEmpty()) dependents.remove(dependency) else dependents[dependency] = remaining
            }
        }
        (previous.files.keys - candidates.keys).forEach { path ->
            val old = files.remove(path)!!
            unlink(path, old)
            replacements[old.relativePath] = null
        }
        var analyzed = 0
        var hits = 0
        var invalidated = replacements.size
        affected.forEach { path ->
            backend.checkCanceled()
            val relative = candidates[path] ?: return@forEach
            val version = backend.version(path)
            val old = files[path]
            if (old != null && old.relativePath == relative && old.version == version &&
                !changes.invalidateAll && path !in changedDependencies) {
                hits++
                return@forEach
            }
            if (old != null) {
                invalidated++
                unlink(path, old)
                if (old.relativePath != relative) replacements[old.relativePath] = null
            }
            val result = backend.discover(path, relative)
            analyzed++
            val dependencies = if (old != null && old.version.sourceStamp == version.sourceStamp &&
                !changes.invalidateAll && path !in changedDependencies) old.dependencies else backend.dependencies(path)
            files[path] = CachedTestFile(relative, version, result, dependencies, backend.awaitingAnalysis(path))
            dependencies.forEach { dependents[it] = dependents[it].orEmpty() + path }
            replacements[relative] = result
        }
        val state = DiscoveryCacheState(files, dependents,
            previous.fullRefreshes + if (changes.rescan) 1 else 0,
            previous.incrementalRefreshes + if (changes.rescan) 0 else 1)
        return DiscoveryCacheUpdate(state, replacements, DiscoveryMetrics(candidates.size, analyzed, hits,
            invalidated, System.nanoTime() - started, state.fullRefreshes, state.incrementalRefreshes))
    }

    companion object {
        fun beneath(path: String, parent: String) = path == parent || path.startsWith(parent.trimEnd('/') + "/")
    }
}
