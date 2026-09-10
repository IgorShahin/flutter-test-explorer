package dev.igorshahin.ui

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.igorshahin.execution.GlobalTestArguments
import dev.igorshahin.settings.TestArgument
import java.awt.BorderLayout
import javax.swing.DefaultCellEditor
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

internal class TestArgumentsEditor : JPanel(BorderLayout(0, JBUI.scale(8))) {
    internal val model = TestArgumentsTableModel(emptyList())
    internal val table = JBTable(model).apply {
        emptyText.text = "No global test arguments. Use Add to create one."
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tableHeader.reorderingAllowed = false
        setShowGrid(false)
        setStriped(true)
        putClientProperty("terminateEditOnFocusLost", true)
        setDefaultEditor(Boolean::class.javaObjectType, DefaultCellEditor(JBCheckBox()))
        columnModel.getColumn(TestArgumentsTableModel.ENABLED_COLUMN).apply {
            minWidth = JBUI.scale(72)
            preferredWidth = JBUI.scale(82)
            maxWidth = JBUI.scale(96)
        }
        columnModel.getColumn(TestArgumentsTableModel.ARGUMENT_COLUMN).cellEditor = DefaultCellEditor(JBTextField())
    }

    init {
        preferredSize = JBUI.size(720, 360)
        add(JBLabel("Global test arguments"), BorderLayout.NORTH)
        add(
            ToolbarDecorator.createDecorator(table)
                .setAddAction { addArgument() }
                .setRemoveAction { removeSelectedArgument() }
                .setMoveUpAction { moveSelectedArgument(-1) }
                .setMoveDownAction { moveSelectedArgument(1) }
                .setRemoveActionUpdater { table.selectedRow >= 0 }
                .setMoveUpActionUpdater { table.selectedRow > 0 }
                .setMoveDownActionUpdater { table.selectedRow in 0 until model.rowCount - 1 }
                .createPanel(),
            BorderLayout.CENTER,
        )
        add(
            JBLabel(
                "<html>Additional arguments are applied to test runs started from Test Explorer.<br>" +
                    "Disabled arguments remain saved but are not passed to the test process.<br>" +
                    "Add one CLI argument per row. Target and name selectors remain managed by Test Explorer.</html>",
            ),
            BorderLayout.SOUTH,
        )
    }

    internal val arguments: List<TestArgument>
        get() = model.arguments().map { TestArgument(it.value.trim(), it.enabled) }

    internal fun validationError(): String? {
        if (model.arguments().any { it.value.isBlank() }) {
            return "Arguments cannot be empty. Remove the empty row or enter a value."
        }
        return GlobalTestArguments.validationError(
            model.arguments().filter(TestArgument::enabled).map { it.value.trim() },
        )
    }

    internal fun replaceArguments(arguments: List<TestArgument>) {
        table.cellEditor?.cancelCellEditing()
        model.replace(arguments)
        if (model.rowCount > 0) table.setRowSelectionInterval(0, 0)
    }

    internal fun stopEditing(): Boolean = !table.isEditing || table.cellEditor.stopCellEditing()

    internal fun dispose() {
        table.cellEditor?.cancelCellEditing()
    }

    private fun addArgument() {
        if (!stopEditing()) return
        val row = model.add(TestArgument())
        table.setRowSelectionInterval(row, row)
        table.editCellAt(row, TestArgumentsTableModel.ARGUMENT_COLUMN)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun removeSelectedArgument() {
        if (!stopEditing()) return
        val row = table.selectedRow
        if (row < 0) return
        model.remove(row)
        if (model.rowCount > 0) {
            val next = row.coerceAtMost(model.rowCount - 1)
            table.setRowSelectionInterval(next, next)
        }
    }

    private fun moveSelectedArgument(delta: Int) {
        if (!stopEditing()) return
        val row = table.selectedRow
        val moved = model.move(row, delta) ?: return
        table.setRowSelectionInterval(moved, moved)
    }
}

internal class TestArgumentsTableModel(initial: List<TestArgument>) : AbstractTableModel() {
    private val rows = initial.map { TestArgument(it.value, it.enabled) }.toMutableList()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 2
    override fun getColumnName(column: Int): String = if (column == ENABLED_COLUMN) "Enabled" else "Argument"
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == ENABLED_COLUMN) Boolean::class.javaObjectType else String::class.java
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        if (columnIndex == ENABLED_COLUMN) rows[rowIndex].enabled else rows[rowIndex].value

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val row = rows[rowIndex]
        if (columnIndex == ENABLED_COLUMN) row.enabled = value as? Boolean ?: false
        else row.value = value?.toString().orEmpty()
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun arguments(): List<TestArgument> = rows.map { TestArgument(it.value, it.enabled) }

    fun add(argument: TestArgument): Int {
        rows += TestArgument(argument.value, argument.enabled)
        val row = rows.lastIndex
        fireTableRowsInserted(row, row)
        return row
    }

    fun remove(row: Int) {
        if (row !in rows.indices) return
        rows.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    fun move(row: Int, delta: Int): Int? {
        val target = row + delta
        if (row !in rows.indices || target !in rows.indices) return null
        val item = rows.removeAt(row)
        rows.add(target, item)
        fireTableRowsUpdated(minOf(row, target), maxOf(row, target))
        return target
    }

    fun replace(arguments: List<TestArgument>) {
        rows.clear()
        rows += arguments.map { TestArgument(it.value, it.enabled) }
        fireTableDataChanged()
    }

    companion object {
        const val ENABLED_COLUMN = 0
        const val ARGUMENT_COLUMN = 1
    }
}
