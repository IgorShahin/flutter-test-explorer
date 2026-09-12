package dev.igorshahin.discovery

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.FileTypeIndex
import com.jetbrains.lang.dart.DartFileType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestFile
import dev.igorshahin.model.DartTestKind
import dev.igorshahin.model.DartTestItem
import dev.igorshahin.model.SourceLocation

internal class DartTestDiscovery(
    private val project: Project,
    private val outlines: TestOutlineProvider,
) {
    fun discoverProject(): List<DartTestFile> {
        val psiManager = PsiManager.getInstance(project)
        val testRoots = findTestRoots()
        LOG.debug("Flutter Test Explorer found ${testRoots.size} test roots")
        val dartFiles = testRoots
            .flatMap { root -> collectDartFiles(root).map { root to it } }
            .distinctBy { (_, file) -> file.path }
        LOG.debug("Flutter Test Explorer will inspect ${dartFiles.size} Dart test files")

        val files = dartFiles.asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .mapNotNull { (testRoot, virtualFile) ->
                val pathInsideRoot = VfsUtilCore.getRelativePath(virtualFile, testRoot, '/')
                    ?: return@mapNotNull null
                val relativePath = project.basePath?.let { base ->
                    virtualFile.path.removePrefix(base.trimEnd('/') + "/")
                } ?: "${testRoot.name}/$pathInsideRoot"
                val dartFile = psiManager.findFile(virtualFile) as? DartFile ?: return@mapNotNull null
                dartFile to relativePath
            }
            .toList()
        return discoverFiles(files)
    }

    internal fun discoverFiles(files: List<Pair<DartFile, String>>): List<DartTestFile> {
        outlines.prepare(files.map { it.first })
        LOG.debug("Flutter Test Explorer checking ${files.size} candidate test entrypoints")
        return files.mapNotNull { (file, relativePath) ->
            ProgressManager.checkCanceled()
            discoverPreparedFile(file, relativePath)
        }.sortedBy(DartTestFile::relativePath)
    }

    /**
     * Finds Flutter package roots through the much smaller pubspec index, then indexes only their
     * test directories. This keeps discovery proportional to test sources instead of every Dart
     * file in a large workspace (including generated files and dependency caches).
     */
    internal fun findTestRoots(projectRoot: VirtualFile? = project.basePath
        ?.let(LocalFileSystem.getInstance()::findFileByPath)): List<VirtualFile> {
        if (projectRoot == null) return emptyList()
        val packageRoots = linkedSetOf<VirtualFile>()
        packageRoots += projectRoot
        ProjectRootManager.getInstance(project).contentRoots
            .filterTo(packageRoots) { VfsUtilCore.isAncestor(projectRoot, it, false) }
        FilenameIndex.getVirtualFilesByName(
            PUBSPEC_FILE,
            GlobalSearchScope.projectScope(project),
        ).asSequence()
            .filter { VfsUtilCore.isAncestor(projectRoot, it, false) }
            .filterNot { hasIgnoredProjectPath(projectRoot, it) }
            .mapNotNullTo(packageRoots, VirtualFile::getParent)

        return packageRoots.asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .flatMap { packageRoot ->
                TEST_ROOTS.asSequence().mapNotNull { name ->
                    packageRoot.findChild(name)?.takeIf(VirtualFile::isDirectory)
                }
            }
            .distinctBy(VirtualFile::getPath)
            .sortedBy(VirtualFile::getPath)
            .toList()
    }

    private fun hasIgnoredProjectPath(projectRoot: VirtualFile, file: VirtualFile): Boolean {
        val relativePath = VfsUtilCore.getRelativePath(file, projectRoot, '/') ?: return true
        return relativePath.split('/').any { segment ->
            segment.startsWith('.') || segment in IGNORED_DIRECTORIES
        }
    }

    internal fun collectDartFiles(testRoot: VirtualFile): List<VirtualFile> {
        val scope = GlobalSearchScopesCore.directoryScope(project, testRoot, true)
            .intersectWith(GlobalSearchScope.projectScope(project))
        // The outline is the source of truth, so cheap candidate collection intentionally includes
        // every Dart file under a standard test root. Helpers receive an empty analyzer test outline
        // and disappear; custom test filenames and entrypoints need no main()/suffix heuristic.
        return FileTypeIndex.getFiles(DartFileType.INSTANCE, scope)
            .filter { !hasIgnoredProjectPath(testRoot, it) }
            .sortedBy(VirtualFile::getPath)
    }

    internal fun isCandidateFile(file: VirtualFile): Boolean = file.fileType == DartFileType.INSTANCE

    fun discoverFile(file: DartFile, relativePath: String = file.name): DartTestFile? {
        outlines.prepare(listOf(file))
        return discoverPreparedFile(file, relativePath)
    }

    private fun discoverPreparedFile(
        file: DartFile,
        relativePath: String,
    ): DartTestFile? {
        val virtualFile = file.virtualFile ?: return null
        if (!file.isValid) return null
        val sourceStamp = TestSourceStamp.current(virtualFile)
        val testItems = outlines.testItems(file).mapNotNull { it.toModel(virtualFile.path, sourceStamp) }
        LOG.debug("Test Explorer received ${testItems.size} analyzer test roots in ${file.name}")
        if (testItems.isEmpty()) return null
        return DartTestFile(
            relativePath = relativePath,
            location = SourceLocation(virtualFile.path, 0, sourceStamp),
            children = testItems,
        )
    }

    private fun DiscoveredTestOutline.toModel(path: String, sourceStamp: Long): DartTestItem? {
        val modelChildren = children.mapNotNull { it.toModel(path, sourceStamp) }
        // A group the analyzer reports with no tests under it is structure without content.
        if (kind == DartTestKind.GROUP && modelChildren.isEmpty()) return null
        return DartTestItem(
            kind = kind,
            name = name,
            location = SourceLocation(path, offset, sourceStamp),
            children = modelChildren,
            runnable = true,
            runtimeNameKnown = runtimeNameKnown,
        )
    }

    private companion object {
        val LOG = Logger.getInstance(DartTestDiscovery::class.java)
        const val PUBSPEC_FILE = "pubspec.yaml"
        val TEST_ROOTS = setOf("test", "integration_test")
        val IGNORED_DIRECTORIES = setOf(".dart_tool", "build")
    }
}
