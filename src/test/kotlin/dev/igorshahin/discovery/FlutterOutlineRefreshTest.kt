package dev.igorshahin.discovery

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.lang.dart.psi.DartFile

class FlutterOutlineRefreshTest : BasePlatformTestCase() {
    fun testRefreshAfterPsiCacheClearStillRecognizesUnchangedSource() {
        val file = myFixture.configureByText("sample_test.dart", "void main() { test('real', () {}); }") as DartFile
        val index = FlutterTestOutlineIndex(project) {}
        try {
            index.recordOutline(file, AnalyzerOutline(0, file.textLength, null, listOf(
                AnalyzerOutline(file.text.indexOf("test("), 1, "UNIT_TEST_TEST", "test(\"real\")", emptyList()))))
            assertEquals(1, index.testItems(file).size)
            val originalText = file.text
            val originalStamp = file.modificationStamp
            WriteAction.run<RuntimeException> { (file as PsiFileImpl).clearCaches() }
            assertTrue("PSI cache stamp must change to reproduce the bug", originalStamp != file.modificationStamp)
            assertEquals(originalText, file.text)
            assertEquals("Refresh must reuse the official outline when only PSI caches changed", 1, index.testItems(file).size)
        } finally { index.dispose() }
    }

    fun testSameLengthUnsavedEditRejectsOldOutlineAndNewOutlineResumesDiscovery() {
        val file = myFixture.configureByText("sample_test.dart", "void main() { test('old!', () {}); }") as DartFile
        val outlineRequests = mutableListOf<String>()
        val index = FlutterTestOutlineIndex(project, {}, outlineRequests::add)
        try {
            val outline = AnalyzerOutline(0, file.textLength, null, listOf(
                AnalyzerOutline(file.text.indexOf("test("), 1, "UNIT_TEST_TEST", "test(\"old!\")", emptyList())))
            index.recordOutline(file, outline)
            val revision = index.revision(file.virtualFile.path)
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText(file.text.replace("old!", "new!"))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertTrue(index.testItems(file).isEmpty())
            assertTrue(index.isAwaitingAnalysis(file.virtualFile.path))
            assertTrue(index.testItems(file).isEmpty())
            assertEquals("One official outline request per edited source digest", listOf(file.virtualFile.path), outlineRequests)
            index.recordOutline(file, outline)
            assertTrue(index.revision(file.virtualFile.path) > revision)
            assertEquals(1, index.testItems(file).size)
            assertFalse(index.isAwaitingAnalysis(file.virtualFile.path))
        } finally { index.dispose() }
    }

    fun testDuplicateOutlineAfterPsiInvalidationDoesNotInvalidateDiscoveryAgain() {
        val file = myFixture.configureByText("sample_test.dart", "void main() {}") as DartFile
        val notifications = mutableListOf<String?>()
        val index = FlutterTestOutlineIndex(project, notifications::add)
        try {
            val outline = AnalyzerOutline(0, file.textLength, null, emptyList())
            index.recordOutline(file, outline)
            val revision = index.revision(file.virtualFile.path)
            WriteAction.run<RuntimeException> { (file as PsiFileImpl).clearCaches() }
            index.recordOutline(file, outline)
            assertEquals(revision, index.revision(file.virtualFile.path))
            assertEquals(1, notifications.size)
            assertTrue(index.testItems(file).isEmpty())
            assertFalse("An analyzed empty file is not still waiting", index.isAwaitingAnalysis(file.virtualFile.path))
        } finally { index.dispose() }
    }

    fun testRepeatedFullRefreshAfterPsiInvalidationKeepsRunnableFileInCache() {
        val file = myFixture.configureByText("sample_test.dart", "void main() { test('real', () {}); }") as DartFile
        val index = FlutterTestOutlineIndex(project) {}
        try {
            index.recordOutline(file, AnalyzerOutline(0, file.textLength, null, listOf(
                AnalyzerOutline(file.text.indexOf("test("), 1, "UNIT_TEST_TEST", "test(\"real\")", emptyList()))))
            val discovery = DartTestDiscovery(project, index)
            val cache = DiscoveryCache(object : DiscoveryBackend {
                override fun version(path: String) = FileVersion(1, index.revision(path))
                override fun discover(path: String, relativePath: String) = discovery.discoverFile(file, relativePath)
                override fun dependencies(path: String) = emptySet<String>()
            })
            var state = DiscoveryCacheState()
            repeat(5) {
                WriteAction.run<RuntimeException> { (file as PsiFileImpl).clearCaches() }
                state = cache.update(state, mapOf(file.virtualFile.path to "test/sample_test.dart"),
                    DiscoveryChanges(rescan = true, invalidateAll = true)).state
                assertEquals("real", state.files.values.single().result!!.children.single().name)
            }
        } finally { index.dispose() }
    }
}
