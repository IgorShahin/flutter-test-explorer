package dev.igorshahin.discovery

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.*

class IndexedDiscoveryTest : BasePlatformTestCase() {
    private fun discovery() = DartTestDiscovery(project, PsiFixtureOutlineProvider())

    fun testDartIndexesIncludeEveryAnalyzerCandidateUnderTestRoots() {
        myFixture.addFileToProject("test/folder/a_test.dart", "void main() {}")
        val entrypoint = myFixture.addFileToProject("test/folder/checks.dart", "void main() {}")
        myFixture.addFileToProject("test/helper.dart", "void helper() {}")
        myFixture.addFileToProject("test/.hidden/noise_test.dart", "void main() {}")
        myFixture.addFileToProject("test/build/generated_test.dart", "void main() {}")
        myFixture.addFileToProject("lib/noise_test.dart", "void main() {}")
        val root = myFixture.tempDirFixture.getFile("test")!!
        assertEquals(listOf("a_test.dart", "checks.dart", "helper.dart"),
            discovery().collectDartFiles(root).map { it.name })
        assertTrue(discovery().isCandidateFile(entrypoint.virtualFile))
        // A helper is not excluded by its name or shape: it simply reports no analyzer tests.
        assertTrue(discovery().isCandidateFile(myFixture.tempDirFixture.getFile("test/helper.dart")!!))
    }

    fun testRootsAreDiscoveredForBothSingleAndNestedPackages() {
        val projectRoot = myFixture.addFileToProject("pubspec.yaml", "name: root_package").virtualFile.parent
        assertEmpty(discovery().findTestRoots(projectRoot))
        myFixture.addFileToProject("test/unit/checks.dart", "void main() {}")
        assertEquals(listOf("test"), discovery().findTestRoots(projectRoot).map { it.name })
        myFixture.addFileToProject("integration_test/e2e/session_test.dart", "void main() {}")
        assertEquals(listOf("integration_test", "test"), discovery().findTestRoots(projectRoot).map { it.name })
        myFixture.addFileToProject("packages/feature/pubspec.yaml", "name: feature")
        myFixture.addFileToProject("packages/feature/integration_test/checks.dart", "void main() {}")
        assertEquals(listOf("integration_test", "packages/feature/integration_test", "test"),
            discovery().findTestRoots(projectRoot).map { it.path.removePrefix(projectRoot.path + "/") })
    }

    fun testDynamicNestedTreePrunesHelpersEmptyFilesAndEmptyGroups() {
        val projectRoot = myFixture.addFileToProject("pubspec.yaml", "name: arbitrary_project").virtualFile.parent
        myFixture.addFileToProject("test/unit/network/checks.dart",
            "void main() { group('protocol', () { group('messages', () { test('decode', () {}); }); }); }")
        myFixture.addFileToProject("integration_test/devices/session_test.dart",
            "void main() { test('connect', () {}); group('empty', () {}); }")
        myFixture.addFileToProject("test/util/helper.dart", "void helper() { utility('noise'); }")
        myFixture.addFileToProject("integration_test/unused/empty_test.dart", "void main() { group('empty', () {}); }")
        val discovery = discovery()
        val files = discovery.findTestRoots(projectRoot).flatMap(discovery::collectDartFiles).map {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it) as DartFile to it.path.removePrefix(projectRoot.path + "/")
        }
        val tree = TestExplorerTreeBuilder().build(discovery.discoverFiles(files))
        assertEquals(listOf("integration_test", "test"), tree.children.map { it.label })
        val labels = flatten(tree).map { it.label }
        assertTrue(labels.containsAll(listOf("unit", "network", "checks.dart", "protocol", "messages", "decode", "devices", "connect")))
        assertFalse(labels.any { it in setOf("util", "helper.dart", "noise", "unused", "empty_test.dart", "empty") })
    }

    fun testIntegrationRootWithoutUnitTestRoot() {
        val projectRoot = myFixture.addFileToProject("pubspec.yaml", "name: integration_only").virtualFile.parent
        myFixture.addFileToProject("integration_test/deep/checks.dart", "void main() { test('only', () {}); }")
        assertEquals(listOf("integration_test"), discovery().findTestRoots(projectRoot).map { it.name })
    }

    fun testCandidateStatusDoesNotDependOnTheShapeOfTheSource() {
        val file = myFixture.addFileToProject("test/checks.dart", "void helper() {}")
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)!!
        // Candidacy is a cheap file-level question. Whether a candidate holds tests is answered by
        // the analyzer outline, so editing a main() in or out must not move files in and out of
        // the watched set and churn analyzer subscriptions.
        assertTrue(discovery().isCandidateFile(file.virtualFile))
        fun replace(text: String) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        replace("void main() { test('new', () {}); }")
        assertTrue(discovery().isCandidateFile(file.virtualFile))
        replace("void helper() {}")
        assertTrue(discovery().isCandidateFile(file.virtualFile))
    }

    private fun flatten(node: ExplorerNode): List<ExplorerNode> = listOf(node) + node.children.flatMap(::flatten)

    fun testDependenciesUseDartImportExportAndPartIndexesTransitively() {
        myFixture.addFileToProject("lib/helper.dart", "export 'transitive.dart'; part 'helper_part.dart';")
        myFixture.addFileToProject("lib/transitive.dart", "void useful() {}")
        myFixture.addFileToProject("lib/helper_part.dart", "part of 'helper.dart';")
        val file = myFixture.addFileToProject("test/a_test.dart", "import '../lib/helper.dart'; void main() {}")
        val outlines = FlutterTestOutlineIndex(project) {}
        try {
            // Light fixture uses temp:///src, not LocalFileSystem or the physical project.basePath.
            val base = file.virtualFile.parent.parent.path
            val backend = IndexedDiscoveryBackend(project, discovery(), outlines, base) {
                myFixture.tempDirFixture.getFile(it.removePrefix("$base/"))
            }
            val dependencies = backend.dependencies(file.virtualFile.path)
            assertTrue(dependencies.any { it.endsWith("/lib/helper.dart") })
            assertTrue(dependencies.any { it.endsWith("/lib/transitive.dart") })
            assertTrue(dependencies.any { it.endsWith("/lib/helper_part.dart") })
        } finally { outlines.dispose() }
    }

    fun testRenameAndMoveInvalidateOldAndNewPaths() {
        val file = myFixture.addFileToProject("test/a_test.dart", "void main() {}").virtualFile
        val base = file.parent.parent.path
        val rename = VFilePropertyChangeEvent(this, file, VirtualFile.PROP_NAME, "a_test.dart", "b_test.dart")
        val renamed = TestDiscoveryEvents.vfs(listOf(rename), base)!!
        assertEquals(setOf("$base/test/a_test.dart", "$base/test/b_test.dart"), renamed.paths)
        assertFalse(renamed.rescan)
        myFixture.addFileToProject("integration_test/dummy.dart", "")
        val moved = TestDiscoveryEvents.vfs(listOf(VFileMoveEvent(this, file,
            myFixture.tempDirFixture.getFile("integration_test")!!)), base)!!
        assertEquals(setOf(file.path, "$base/integration_test/a_test.dart"), moved.paths)
    }

    fun testWarmPsiBenchmarkFullDiscoveryVersusSingleFileCacheUpdate() {
        val files = (1..200).map { index ->
            val path = "test/perf/${index}_test.dart"
            val body = (1..5).joinToString(" ") { "test('case $it', () {});" }
            (myFixture.addFileToProject(path, "void main() { group('suite $index', () { $body }); }") as DartFile) to path
        }
        val discovery = discovery()
        val byPath = files.associate { it.first.virtualFile.path to it }
        val candidates = byPath.mapValues { it.value.second }
        val changed = candidates.keys.first()
        var stamp = 1L
        val cache = DiscoveryCache(object : DiscoveryBackend {
            override fun version(path: String) = FileVersion(if (path == changed) stamp else 1, 0)
            override fun discover(path: String, relativePath: String) = discovery.discoverFile(byPath.getValue(path).first, relativePath)
            override fun dependencies(path: String) = emptySet<String>()
        })
        var state = cache.update(DiscoveryCacheState(), candidates, DiscoveryChanges(rescan = true)).state
        val initial = TestExplorerTreeBuilder().build(discovery.discoverFiles(files))
        val fullTimes = mutableListOf<Long>()
        val incrementalTimes = mutableListOf<Long>()
        repeat(7) { iteration ->
            var start = System.nanoTime()
            TestExplorerTreeBuilder().build(discovery.discoverFiles(files))
            val full = System.nanoTime() - start
            stamp++
            start = System.nanoTime()
            val update = cache.update(state, candidates, DiscoveryChanges(paths = setOf(changed)))
            TestModelPatcher().replace(initial, update.replacements)
            val incremental = System.nanoTime() - start
            assertEquals(1, update.metrics.analyzed)
            state = update.state
            if (iteration >= 2) { fullTimes += full; incrementalTimes += incremental }
        }
        println("DISCOVERY_BENCHMARK warm PSI, stubbed recognition, 200 files/1000 tests: fullMedianMs=${fullTimes.sorted()[2] / 1_000_000.0}, incrementalMedianMs=${incrementalTimes.sorted()[2] / 1_000_000.0}; analyzed 200 -> 1")
    }
}
