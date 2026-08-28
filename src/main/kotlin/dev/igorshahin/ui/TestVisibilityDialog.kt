package dev.igorshahin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.igorshahin.filter.SelectionState
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.ExplorerNode
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree

internal class TestVisibilityDialog(
    project: Project,
    model: ExplorerNode,
    private val previousExclusions: Set<String>,
) : DialogWrapper(project) {
    private val knownIds = mutableSetOf<String>()
    private fun build(node: ExplorerNode, parentExcluded: Boolean = false): CheckedTreeNode {
        knownIds += node.id
        val excluded = parentExcluded || node.id in previousExclusions
        return CheckedTreeNode(node).apply {
            isChecked = !excluded && TestVisibility.state(node, previousExclusions) != SelectionState.EXCLUDED
            node.children.forEach { add(build(it, excluded)) }
        }
    }
    private val root = build(model)
    internal val tree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                                       leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? CheckedTreeNode)?.userObject as? ExplorerNode ?: return
            textRenderer.append(node.label)
        }
    }, root, CheckboxTreeBase.CheckPolicy(true, true, true, false)).apply {
        isRootVisible = true
        showsRootHandles = true
        TreeUtil.expand(this, 4)
    }

    init {
        title = "Test Explorer Visibility"
        setOKButtonText("Apply")
        init()
    }

    fun excludedIds(): Set<String> {
        // Preserve stale IDs: a temporarily missing file may return after a branch switch.
        val result = (previousExclusions - knownIds).toMutableSet()
        fun collect(node: CheckedTreeNode) {
            if (!node.isChecked) {
                result += (node.userObject as ExplorerNode).id
            } else node.children().toList().forEach { collect(it as CheckedTreeNode) }
        }
        collect(root)
        return result
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = JBUI.size(620, 500)
        add(JBLabel("Checked tests are visible. Checking a parent includes all its descendants."), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    }
}
