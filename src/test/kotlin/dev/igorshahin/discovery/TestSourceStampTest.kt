package dev.igorshahin.discovery

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.*

class TestSourceStampTest : BasePlatformTestCase() {
    private fun discovery() = DartTestDiscovery(project, PsiFixtureOutlineProvider())

    fun testPsiCacheInvalidationDoesNotInvalidateRunLocations() {
        val file = myFixture.configureByText("sample_test.dart", "void main() { test('stable', () {}); }") as DartFile
        val before = discovery().discoverFile(file)!!
        val psiStamp = file.modificationStamp
        WriteAction.run<RuntimeException> { (file as PsiFileImpl).clearCaches() }
        assertTrue(file.modificationStamp != psiStamp)
        assertEquals(before.location.modificationStamp, TestSourceStamp.current(file.virtualFile))
        assertEquals(before.children.single().location.modificationStamp, TestSourceStamp.current(file.virtualFile))
    }

    fun testUnsavedSameLengthEditChangesSourceVersionAndDiscoveryUsesEditorText() {
        val file = myFixture.addFileToProject("test/sample_test.dart", "void main() { test('old!', () {}); }") as DartFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val diskBefore = file.virtualFile.contentsToByteArray().toList()
        val before = discovery().discoverFile(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("old!", "new!")) }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertTrue(before.location.modificationStamp != TestSourceStamp.current(file.virtualFile))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = discovery().discoverFile(file)!!
        assertEquals("new!", after.children.single().name)
        assertEquals(TestSourceStamp.current(file.virtualFile), after.location.modificationStamp)
        assertEquals(diskBefore, file.virtualFile.contentsToByteArray().toList()) // discovery never saves
    }

    fun testOffsetOnlyEditsKeepVisibilityIdsButUseNewLocations() {
        val file = myFixture.configureByText("sample_test.dart", "void main() { test('stable', () {}); }") as DartFile
        val before = TestExplorerTreeBuilder().build(listOf(discovery().discoverFile(file, "test/sample_test.dart")!!))
        val selected = TestVisibility.leaves(before).single()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// shifted\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = TestExplorerTreeBuilder().build(listOf(discovery().discoverFile(file, "test/sample_test.dart")!!))
        val refreshed = TestVisibility.leaves(after).single()
        assertEquals(selected.id, refreshed.id)
        assertTrue(refreshed.location!!.offset > selected.location!!.offset)
        assertEmpty(TestVisibility.leaves(TestVisibility.apply(after, setOf(selected.id))))
    }

    fun testOpeningDocumentWithoutEditingKeepsSourceStamp() {
        val file = myFixture.addFileToProject("test/unopened_test.dart", "void main() { test('stable', () {}); }")
        val stamp = TestSourceStamp.current(file.virtualFile)
        FileDocumentManager.getInstance().getDocument(file.virtualFile)
        assertEquals(stamp, TestSourceStamp.current(file.virtualFile))
    }
}
