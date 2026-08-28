package dev.igorshahin.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.util.ui.ThreeStateCheckBox
import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.*

class TestVisibilityDialogTest : BasePlatformTestCase() {
    private fun model(): ExplorerNode {
        val location = SourceLocation("/project/test/a_test.dart", 0)
        return TestExplorerTreeBuilder().build(listOf(DartTestFile("test/a_test.dart", location,
            listOf(DartTestItem(DartTestKind.TEST, "first", location), DartTestItem(DartTestKind.TEST, "second", location)))))
    }

    fun testNativeTriStateAndParentToggle() {
        val model = model()
        val hidden = TestVisibility.leaves(model).first().id
        val dialog = TestVisibilityDialog(project, model, setOf(hidden, "stale"))
        try {
            val tree = dialog.tree
            val root = tree.model.root as CheckedTreeNode
            val renderer = tree.cellRenderer as CheckboxTree.CheckboxTreeCellRenderer
            renderer.getTreeCellRendererComponent(tree, root, false, true, false, 0, false)
            assertEquals(ThreeStateCheckBox.State.DONT_CARE, renderer.threeStateCheckBox.state)
            tree.setNodeState(root, false)
            assertEquals(setOf(model.id, "stale"), dialog.excludedIds())
            tree.setNodeState(root, true)
            assertEquals(setOf("stale"), dialog.excludedIds())
        } finally { dialog.close(1) }
    }

    fun testCancelledDialogDoesNotChangeOriginalExclusions() {
        val model = model()
        val original = setOf(TestVisibility.leaves(model).first().id)
        val dialog = TestVisibilityDialog(project, model, original)
        val tree = dialog.tree
        tree.setNodeState(tree.model.root as CheckedTreeNode, true)
        dialog.close(1)
        assertEquals(1, original.size)
    }

}
