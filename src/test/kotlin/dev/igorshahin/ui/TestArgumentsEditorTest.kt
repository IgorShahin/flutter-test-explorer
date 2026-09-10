package dev.igorshahin.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import dev.igorshahin.settings.TestArgument
import javax.swing.DefaultCellEditor

class TestArgumentsEditorTest : BasePlatformTestCase() {
    fun testTableModelAddsEditsTogglesMovesAndRemovesWithoutDeduplication() {
        val model = TestArgumentsTableModel(listOf(
            TestArgument("--first", true),
            TestArgument("--second", true),
        ))

        val added = model.add(TestArgument("--first", false))
        model.setValueAt("--edited", added, TestArgumentsTableModel.ARGUMENT_COLUMN)
        model.setValueAt(true, added, TestArgumentsTableModel.ENABLED_COLUMN)
        assertEquals(1, model.move(added, -1))
        model.remove(0)

        assertEquals(listOf(
            TestArgument("--edited", true),
            TestArgument("--second", true),
        ), model.arguments())
    }

    fun testEditorUsesNativeControlsAndAcceptsArbitraryArgumentContents() {
        val editor = TestArgumentsEditor()
        try {
            assertTrue((editor.table.getDefaultEditor(Boolean::class.javaObjectType) as DefaultCellEditor).component is JBCheckBox)
            assertTrue((editor.table.columnModel.getColumn(TestArgumentsTableModel.ARGUMENT_COLUMN).cellEditor
                as DefaultCellEditor).component is JBTextField)
            assertNotNull(ToolbarDecorator.findAddButton(editor))
            assertNotNull(ToolbarDecorator.findRemoveButton(editor))
            assertNotNull(ToolbarDecorator.findUpButton(editor))
            assertNotNull(ToolbarDecorator.findDownButton(editor))

            val row = editor.model.add(TestArgument())
            assertNotNull(editor.validationError())
            editor.model.setValueAt("  --custom=two words \"quoted\"  ", row, TestArgumentsTableModel.ARGUMENT_COLUMN)
            assertNull(editor.validationError())
            assertEquals("--custom=two words \"quoted\"", editor.arguments.single().value)
        } finally {
            editor.dispose()
        }
    }
}
