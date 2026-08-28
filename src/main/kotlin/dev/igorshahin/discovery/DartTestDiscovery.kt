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
import com.jetbrains.lang.dart.ide.index.DartComponentIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiErrorElement
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartComponent
import com.jetbrains.lang.dart.psi.DartFile
import com.jetbrains.lang.dart.psi.DartFunctionDeclarationWithBody
import com.jetbrains.lang.dart.psi.DartFunctionDeclarationWithBodyOrNative
import com.jetbrains.lang.dart.psi.DartStringLiteralExpression
import dev.igorshahin.model.DartTestFile
import dev.igorshahin.model.DartTestItem
import dev.igorshahin.model.DartTestKind
import dev.igorshahin.model.SourceLocation

internal class DartTestDiscovery(
    private val project: Project,
    private val runnabilityValidator: TestRunnabilityValidator,
) {
    private val nameResolver = DartTestNameResolver()

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
        runnabilityValidator.prepare(files.map { it.first })
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
        // The official producers do not require the suffix inside test roots. Use Dart's
        // declaration index to include other entrypoints without loading every helper's PSI.
        // Neither an indexed main nor a filename is evidence of a runnable test.
        val entrypoints = DartComponentIndex.getAllFiles("main", scope).toSet()
        return FileTypeIndex.getFiles(DartFileType.INSTANCE, scope)
            .filter { (it.name.endsWith(TEST_FILE_SUFFIX) || it in entrypoints) && !hasIgnoredProjectPath(testRoot, it) }
            .sortedBy(VirtualFile::getPath)
    }

    internal fun isCandidateFile(file: VirtualFile): Boolean = file.fileType == DartFileType.INSTANCE &&
        (file.name.endsWith(TEST_FILE_SUFFIX) ||
            DartComponentIndex.getAllFiles("main", GlobalSearchScope.fileScope(project, file)).contains(file))

    fun discoverFile(file: DartFile, relativePath: String = file.name): DartTestFile? {
        runnabilityValidator.prepare(listOf(file))
        return discoverPreparedFile(file, relativePath)
    }

    private fun discoverPreparedFile(
        file: DartFile,
        relativePath: String,
    ): DartTestFile? {
        val virtualFile = file.virtualFile ?: return null
        if (!file.isValid) return null
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return null
        val calls = PsiTreeUtil.findChildrenOfType(file, DartCallExpression::class.java)
            .sortedBy { it.textOffset }
        if (calls.isEmpty()) return null

        // One classification/resolve per call, even inside deeply nested groups. Read-action local PSI only.
        val kinds = calls.associateWith {
            ProgressManager.checkCanceled()
            runnabilityValidator.kind(it)
        }

        val recognized = calls.mapNotNull { call ->
            ProgressManager.checkCanceled()
            val kind = kinds[call] ?: return@mapNotNull null
            if (!isRegistrationContext(call, kinds)) return@mapNotNull null
            val firstArgument = call.arguments?.argumentList?.expressionList?.firstOrNull()
            val staticName = firstArgument is DartStringLiteralExpression &&
                firstArgument.longTemplateEntryList.isEmpty() && firstArgument.shortTemplateEntryList.isEmpty()
            if (!staticName && kind != DartTestKind.GROUP) return@mapNotNull null
            val name = if (staticName) nameResolver.resolve(firstArgument) else
                "group(${firstArgument?.text?.take(80) ?: "…"})"
            call to MutableTestItem(
                kind = kind,
                name = name,
                location = SourceLocation(virtualFile.path, call.textOffset, file.modificationStamp),
                runnable = staticName && runnabilityValidator.isRunnable(call, name),
                nameIsStatic = staticName,
                runtimeNameKnown = staticName && nameResolver.isRuntimeNameKnown(firstArgument),
            )
        }.toMap(LinkedHashMap())

        if (recognized.isEmpty()) return null
        val roots = mutableListOf<MutableTestItem>()
        recognized.forEach { (call, item) ->
            val parentGroup = findParentGroup(call, recognized)
            if (parentGroup == null) roots += item else parentGroup.children += item
        }

        val runnableRoots = roots.mapNotNull(MutableTestItem::freezeRunnable)
        LOG.debug("Test Explorer kept ${runnableRoots.size} runnable top-level entities in ${file.name}")
        if (runnableRoots.isEmpty()) return null
        return DartTestFile(
            relativePath = relativePath,
            location = SourceLocation(virtualFile.path, 0, file.modificationStamp),
            children = runnableRoots,
        )
    }

    private fun isRegistrationContext(call: DartCallExpression, kinds: Map<DartCallExpression, DartTestKind?>): Boolean {
        var parent = call.parent
        while (parent != null && parent !== call.containingFile) {
            // A test inside a helper, setUp, or another test body is not a registration target.
            if (parent is DartFunctionDeclarationWithBody || parent is DartFunctionDeclarationWithBodyOrNative) {
                return parent.name == "main" && parent.parent is DartFile
            }
            if (parent is DartComponent && parent.name != null) return false
            if (parent is DartCallExpression) {
                if (kinds[parent] != DartTestKind.GROUP) return false
            }
            parent = parent.parent
        }
        return false
    }

    private fun findParentGroup(
        call: DartCallExpression,
        recognized: Map<DartCallExpression, MutableTestItem>,
    ): MutableTestItem? {
        var parent = call.parent
        while (parent != null && parent !== call.containingFile) {
            if (parent is DartCallExpression) {
                val item = recognized[parent]
                if (item?.kind == DartTestKind.GROUP) return item
            }
            parent = parent.parent
        }
        return null
    }

    private class MutableTestItem(
        val kind: DartTestKind,
        val name: String,
        val location: SourceLocation,
        val runnable: Boolean,
        val nameIsStatic: Boolean,
        val runtimeNameKnown: Boolean,
        val children: MutableList<MutableTestItem> = mutableListOf(),
    ) {
        fun freezeRunnable(): DartTestItem? {
            val runnableChildren = children.mapNotNull(MutableTestItem::freezeRunnable)
            val keep = when (kind) {
                DartTestKind.GROUP -> runnableChildren.isNotEmpty()
                DartTestKind.TEST, DartTestKind.TEST_WIDGETS -> runnable
            }
            if (!keep) return null
            return DartTestItem(
                kind = kind,
                name = name,
                location = location,
                children = runnableChildren,
                runnable = runnable,
                nameIsStatic = nameIsStatic,
                runtimeNameKnown = runtimeNameKnown,
            )
        }
    }

    private companion object {
        val LOG = Logger.getInstance(DartTestDiscovery::class.java)
        const val PUBSPEC_FILE = "pubspec.yaml"
        const val DART_EXTENSION = "dart"
        const val TEST_FILE_SUFFIX = "_test.dart"
        val TEST_ROOTS = setOf("test", "integration_test")
        val IGNORED_DIRECTORIES = setOf(".dart_tool", "build")
    }
}
