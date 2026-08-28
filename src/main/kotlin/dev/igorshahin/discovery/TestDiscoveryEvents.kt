package dev.igorshahin.discovery

import com.intellij.openapi.vfs.newvfs.events.*

/** Event classification uses paths/metadata only; no file IO, PSI or index access on the EDT. */
internal object TestDiscoveryEvents {
    fun vfs(events: List<VFileEvent>, basePath: String): DiscoveryChanges? {
        var result: DiscoveryChanges? = null
        events.forEach { event ->
            val paths = when (event) {
                is VFileMoveEvent -> setOf(event.oldPath, event.newPath)
                is VFilePropertyChangeEvent -> if (event.isRename) setOf(event.oldPath, event.newPath) else emptySet()
                is VFileCopyEvent -> setOf(event.newParent.path + "/" + event.newChildName)
                else -> setOf(event.path)
            }.filter { DiscoveryCache.beneath(it, basePath) }
                .filter { path ->
                    isConfiguration(path) || path.removePrefix(basePath.trimEnd('/') + "/")
                        .split('/').none { it.startsWith('.') || it == "build" }
                }.toSet()
            if (paths.isEmpty()) return@forEach
            val directory = if (event is VFileCreateEvent) event.isDirectory else event.file?.isDirectory == true
            val config = paths.any(::isConfiguration)
            val change = when {
                config -> DiscoveryChanges(rescan = true, invalidateAll = true)
                directory -> DiscoveryChanges(subtrees = paths,
                    rescan = paths.any { !insideTestRoot(it) } &&
                        !(event is VFileCreateEvent && event.isEmptyDirectory && paths.none { it.substringAfterLast('/') in TEST_ROOT_NAMES }))
                paths.any { it.endsWith(".dart") } -> DiscoveryChanges(paths = paths)
                else -> return@forEach
            }
            result = result?.merge(change) ?: change
        }
        return result
    }

    fun isConfiguration(path: String): Boolean = path.substringAfterLast('/') in
        setOf("pubspec.yaml", "pubspec.lock", "package_config.json", ".packages", "analysis_options.yaml")

    private fun insideTestRoot(path: String) = path.contains("/test/") || path.contains("/integration_test/")
    private val TEST_ROOT_NAMES = setOf("test", "integration_test")
}
