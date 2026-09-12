package dev.igorshahin.discovery

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.lang.dart.ide.index.DartImportAndExportIndex
import com.jetbrains.lang.dart.ide.index.DartPartUriIndex
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.util.DartUrlResolver
import dev.igorshahin.discovery.DiscoveryCache.Companion.beneath
import dev.igorshahin.model.DartTestFile
import java.nio.file.Path

/** All methods run in a committed, smart-mode read action. Only discover() requests candidate PSI. */
internal class IndexedDiscoveryBackend(private val project: Project, private val discovery: DartTestDiscovery,
                                        private val outlines: FlutterTestOutlineIndex,
                                        private val base: String = project.basePath.orEmpty().trimEnd('/'),
                                        private val findFile: (String) -> VirtualFile? = LocalFileSystem.getInstance()::findFileByPath,
) : DiscoveryBackend {
    private var roots: List<VirtualFile> = emptyList()
    private var analyzerPaths: Set<String> = emptySet()
    private data class Imports(val sourceStamp: Long, val paths: Set<String>)
    private val directDependencies = mutableMapOf<String, Imports>()

    fun candidates(previous: DiscoveryCacheState, changes: DiscoveryChanges): Map<String, String> {
        if (changes.invalidateAll) directDependencies.clear() else {
            changes.paths.forEach(directDependencies::remove)
            if (changes.subtrees.isNotEmpty()) directDependencies.keys.removeAll { path -> changes.subtrees.any { beneath(path, it) } }
        }
        if (changes.rescan) roots = discovery.findTestRoots()
        val result = previous.files.mapValuesTo(linkedMapOf()) { it.value.relativePath }
        fun add(file: VirtualFile) {
            if (eligible(file)) result[file.path] = file.path.removePrefix("$base/")
        }
        if (changes.rescan) {
            result.clear()
            roots.forEach { discovery.collectDartFiles(it).forEach(::add) }
        } else {
            changes.paths.forEach { path ->
                result.remove(path)
                findFile(path)?.let(::add)
            }
            changes.subtrees.forEach { path ->
                result.keys.removeAll { beneath(it, path) }
                val directory = findFile(path)
                if (directory?.isDirectory == true && roots.any { beneath(path, it.path) || beneath(it.path, path) }) {
                    discovery.collectDartFiles(directory).forEach(::add)
                }
            }
        }
        // Subscriptions describe the entire candidate set, not just today's cache misses.
        if (changes.rescan || result.keys != previous.files.keys) {
            val analyzerFiles = result.keys.mapNotNull(findFile)
            analyzerPaths = analyzerFiles.map { it.path }.toSet()
            outlines.watch(analyzerFiles)
        }
        return result
    }

    private fun eligible(file: VirtualFile): Boolean = file.isValid && !file.isDirectory &&
        ProjectFileIndex.getInstance(project).isInContent(file) &&
        roots.any { beneath(file.path, it.path) } &&
        file.path.removePrefix("$base/").split('/').none { it.startsWith('.') || it == "build" } &&
        discovery.isCandidateFile(file)

    override fun version(path: String): FileVersion {
        val file = findFile(path) ?: return FileVersion(-1, outlines.revision(path))
        // A document is authoritative while loaded, including unsaved edits. Saving that same
        // document must not invalidate the result for a second time through its VFS event.
        return FileVersion(sourceStamp(file), outlines.revision(path))
    }

    override fun discover(path: String, relativePath: String): DartTestFile? {
        val virtualFile = findFile(path) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? DartFile ?: return null
        return discovery.discoverFile(file, relativePath)
    }

    override fun dependencies(path: String): Set<String> {
        // The Dart Analysis Server tracks semantic dependencies and republishes affected test
        // outlines for both Dart and Flutter packages. Do not duplicate its import graph here.
        if (path in analyzerPaths) return emptySet()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            checkCanceled()
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val stamp = findFile(current)?.let(::sourceStamp) ?: -1
            val entry = directDependencies[current]?.takeIf { it.sourceStamp == stamp }
                ?: Imports(stamp, imports(current)).also { directDependencies[current] = it }
            entry.paths.forEach { if (it !in visited) pending.add(it) }
        }
        visited.remove(path)
        return visited
    }

    private fun imports(path: String): Set<String> {
        val file = findFile(path) ?: return emptySet()
        val resolver = DartUrlResolver.getInstance(project, file)
        val uris = DartImportAndExportIndex.getImportAndExportInfos(project, file).map { it.uri } +
            DartPartUriIndex.getPartUris(project, file)
        return uris.mapNotNull { uri ->
            if (uri.startsWith("dart:")) return@mapNotNull null
            val resolved = resolver.findFileByDartUrl(uri)?.path
                // Keep missing relative dependencies so their later creation invalidates importers.
                ?: if (':' !in uri) Path.of(path).parent.resolve(uri).normalize().toString() else null
            resolved?.takeIf { beneath(it, base) }
        }.toSet()
    }

    override fun checkCanceled() = ProgressManager.checkCanceled()

    override fun awaitingAnalysis(path: String): Boolean = path in analyzerPaths && outlines.isAwaitingAnalysis(path)

    private fun sourceStamp(file: VirtualFile) =
        TestSourceStamp.current(file)
}
