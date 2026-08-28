package dev.igorshahin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.jetbrains.lang.dart.DartFileType
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/** Renderers are paint stamps, not live controls. Resolve the hit row afresh, never use the
 * renderer's last node to dispatch an action. Keyboard/popup execution stays with the panel. */
internal class InlineRunTree(
    private val canRun: () -> Boolean,
    private val runNode: (String) -> Unit,
) : Tree() {
    private var hoveredId: String? = null
    private var pressedId: String? = null

    init {
        // Kotlin can resolve JTree's protected field here. Use the setter so the native TreeUI
        // receives the property change and replaces its cached default renderer immediately.
        setCellRenderer(RowRenderer())
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    internal fun runBounds(row: Int): Rectangle? {
        val path = getPathForRow(row) ?: return null
        val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode ?: return null
        if (!hasInlineRun(node)) return null
        return getPathBounds(path)?.let { Rectangle(it.x, it.y, JBUI.scale(BUTTON_SIZE), it.height) }
    }

    private fun hitNode(event: MouseEvent): ExplorerNode? {
        val row = getRowForLocation(event.x, event.y)
        if (runBounds(row)?.contains(event.point) != true) return null
        return (getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode
    }

    override fun getToolTipText(event: MouseEvent): String? = hitNode(event)?.let { "Run ${it.label}" }

    override fun processMouseEvent(event: MouseEvent) {
        if (event.id == MouseEvent.MOUSE_EXITED) updateHover(null)
        if (event.button == MouseEvent.BUTTON1) {
            val hit = hitNode(event)
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> if (hit != null) {
                    pressedId = hit.id
                    event.consume()
                    return
                }
                MouseEvent.MOUSE_RELEASED -> if (pressedId != null) {
                    val pressed = pressedId
                    pressedId = null
                    if (hit != null && hit.id == pressed && event.clickCount == 1 && canRun()) runNode(hit.id)
                    event.consume()
                    return
                }
                MouseEvent.MOUSE_CLICKED -> if (hit != null) {
                    event.consume()
                    return
                }
            }
        }
        super.processMouseEvent(event)
    }

    override fun processMouseMotionEvent(event: MouseEvent) {
        updateHover(hitNode(event)?.id)
        if (pressedId == null) super.processMouseMotionEvent(event)
    }

    private fun updateHover(id: String?) {
        if (hoveredId == id) return
        hoveredId = id
        cursor = if (id != null && canRun()) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint(visibleRect)
    }

    private inner class RowRenderer : JPanel(BorderLayout()), TreeCellRenderer {
        private val action = object : DumbAwareAction("Run", "Run this test target", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = Unit // renderer only; hit testing above dispatches by ID
        }
        private val button = object : ActionButton(action, action.templatePresentation.clone(),
            "FlutterTestExplorer.Inline", JBUI.size(BUTTON_SIZE)) {
            fun configure(hover: Boolean, enabled: Boolean) {
                myRollover = hover
                presentation.isEnabled = enabled
            }
        }.apply {
            setLook(ActionButtonLook.INPLACE_LOOK)
            isFocusable = false
        }
        private val label = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                                               leaf: Boolean, row: Int, hasFocus: Boolean) {
                val node = (value as? DefaultMutableTreeNode)?.userObject as? ExplorerNode ?: return
                icon = when (node.kind) {
                    ExplorerNodeKind.ROOT -> AllIcons.Nodes.Project
                    ExplorerNodeKind.DIRECTORY -> AllIcons.Nodes.Folder
                    ExplorerNodeKind.FILE -> DartFileType.INSTANCE.icon
                    ExplorerNodeKind.GROUP, ExplorerNodeKind.TEST, ExplorerNodeKind.TEST_WIDGETS -> AllIcons.Nodes.Test
                    ExplorerNodeKind.MESSAGE -> AllIcons.General.Information
                }
                append(node.label, if (node.kind == ExplorerNodeKind.MESSAGE ||
                    (node.kind == ExplorerNodeKind.GROUP && !node.runnable)) SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        init {
            isOpaque = false
            add(button, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        override fun getTreeCellRendererComponent(tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                                                   leaf: Boolean, row: Int, hasFocus: Boolean): JPanel {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ExplorerNode
            label.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            button.isVisible = node != null && hasInlineRun(node)
            button.configure(node?.id == hoveredId, canRun())
            // Explicit getter: Kotlin otherwise resolves JPanel's nullable protected field before
            // Swing has lazily initialized accessibility (e.g. expandable-row tooltip rendering).
            getAccessibleContext().accessibleName = node?.label
            getAccessibleContext().accessibleDescription = if (button.isVisible) "Runnable; Shift+F10 runs the selected target" else null
            return this
        }
    }

    companion object {
        const val BUTTON_SIZE = 24
        fun hasInlineRun(node: ExplorerNode): Boolean = node.runnable && node.kind in setOf(
            ExplorerNodeKind.FILE, ExplorerNodeKind.GROUP, ExplorerNodeKind.TEST, ExplorerNodeKind.TEST_WIDGETS,
        )
    }
}
