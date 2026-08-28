package dev.igorshahin.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.igorshahin.model.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class InlineRunTreeTest : BasePlatformTestCase() {
    fun testNativeUiUsesInlineRendererFromFirstPaint() {
        val tree = testTree(mutableListOf())
        try {
            val path = tree.getPathForRow(1)
            val component = tree.cellRenderer.getTreeCellRendererComponent(tree,
                path.lastPathComponent, false, false, true, 1, false)
            assertEquals(component.preferredSize.width, tree.getPathBounds(path)!!.width)
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testRendererSurvivesNativeUiUpdate() {
        val tree = InlineRunTree({ true }) {}
        try {
            val renderer = tree.cellRenderer
            tree.updateUI()
            assertSame(renderer, tree.cellRenderer)
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testRendererSupportsNativeExpandableRowTooltipBeforeAccessibilityInitialization() {
        val tree = InlineRunTree({ true }) {}
        try {
            val node = DefaultMutableTreeNode(ExplorerNode(ExplorerNodeKind.TEST, "run me", id = "one", runnable = true))
            val component = tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
            assertEquals("run me", component.accessibleContext.accessibleName)
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testIconRunsHitNodeWithoutSelectingItAndDoubleClickDoesNotRunTwice() {
        val runs = mutableListOf<String>()
        val tree = testTree(runs)
        try {
            tree.setSelectionRow(1)
            val selected = tree.selectionPath
            val bounds = tree.runBounds(2)!!
            click(tree, bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            assertEquals(listOf("second"), runs)
            assertEquals(selected, tree.selectionPath)
            click(tree, bounds.x + 5, bounds.y + 5, 2)
            assertEquals(1, runs.size)
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testRowSelectionDoesNotRunAndNormalDoubleClickReachesNavigationListener() {
        val runs = mutableListOf<String>()
        val tree = testTree(runs)
        try {
            var navigations = 0
            tree.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) navigations++ }
            })
            val bounds = tree.runBounds(1)!!
            click(tree, bounds.x + bounds.width + 20, bounds.y + bounds.height / 2)
            assertEquals(1, tree.selectionRows!!.single())
            assertTrue(runs.isEmpty())
            click(tree, bounds.x + bounds.width + 20, bounds.y + bounds.height / 2, 2)
            assertEquals(1, navigations)
            assertTrue(runs.isEmpty())
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testDisabledActionAndDragOffIconDoNotRun() {
        val runs = mutableListOf<String>()
        val tree = testTree(runs, enabled = false)
        try {
            val bounds = tree.runBounds(1)!!
            click(tree, bounds.x + 5, bounds.y + 5)
            assertTrue(runs.isEmpty())
            assertNull(tree.runBounds(0))
            assertFalse(InlineRunTree.hasInlineRun(ExplorerNode(ExplorerNodeKind.GROUP, "structural")))
            assertTrue(InlineRunTree.hasInlineRun(ExplorerNode(ExplorerNodeKind.FILE, "file", runnable = true)))
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    fun testSubtreePatchPreservesSwingIdentityExpansionSelectionAndHiddenState() {
        val runs = mutableListOf<String>()
        val tree = testTree(runs)
        try {
            val root = ExplorerNode(ExplorerNodeKind.ROOT, "project", id = "root", children = listOf(
                ExplorerNode(ExplorerNodeKind.GROUP, "group", id = "group", runnable = true, children = listOf(
                    ExplorerNode(ExplorerNodeKind.TEST, "first", id = "first", runnable = true))),
                ExplorerNode(ExplorerNodeKind.TEST, "second", id = "second", runnable = true)))
            val updater = TestTreeUpdater(tree)
            updater.apply(root)
            tree.expandRow(1)
            tree.setSelectionRow(2)
            val selected = tree.lastSelectedPathComponent
            val model = tree.model
            updater.apply(root.copy(children = root.children + ExplorerNode(ExplorerNodeKind.TEST, "third", id = "third")))
            assertSame(model, tree.model)
            assertSame(selected, tree.lastSelectedPathComponent)
            assertTrue(tree.isExpanded(1))
            updater.apply(root.copy(children = root.children.drop(1)))
            updater.apply(root)
            assertEquals("first", ((tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as ExplorerNode).id)
            assertTrue(tree.isExpanded(1))
        } finally { javax.swing.ToolTipManager.sharedInstance().unregisterComponent(tree) }
    }

    private fun testTree(runs: MutableList<String>, enabled: Boolean = true): InlineRunTree = InlineRunTree({ enabled }, runs::add).apply {
        val root = DefaultMutableTreeNode(ExplorerNode(ExplorerNodeKind.ROOT, "project", id = "root"))
        listOf("first", "second").forEach { root.add(DefaultMutableTreeNode(
            ExplorerNode(ExplorerNodeKind.TEST, it, id = it, runnable = true))) }
        model = DefaultTreeModel(root)
        setSize(600, 400)
        expandPath(TreePath(root.path))
        doLayout()
    }

    private fun click(tree: InlineRunTree, x: Int, y: Int, count: Int = 1) {
        listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED).forEach { id ->
            tree.dispatchEvent(MouseEvent(tree, id, System.currentTimeMillis(), 0, x, y, count, false, MouseEvent.BUTTON1))
        }
    }
}
