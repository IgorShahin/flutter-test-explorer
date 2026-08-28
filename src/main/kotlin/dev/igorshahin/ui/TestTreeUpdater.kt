package dev.igorshahin.ui

import com.intellij.util.ui.tree.TreeUtil
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Minimal Swing mutations; unchanged immutable branches are skipped by reference identity. */
internal class TestTreeUpdater(private val tree: JTree) {
    private val byId = mutableMapOf<String, DefaultMutableTreeNode>()
    private var initialized = false
    private var shownTests = false
    private var updating = false
    private val expandedIds = mutableSetOf<String>()
    private var selectedId: String? = null
    var lastVisitedNodes: Int = 0
        private set

    init {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (!updating) (event.path.lastPathComponent as? DefaultMutableTreeNode)?.node()?.let { expandedIds += it.id }
            }
            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (!updating) (event.path.lastPathComponent as? DefaultMutableTreeNode)?.node()?.let { expandedIds -= it.id }
            }
        })
        tree.addTreeSelectionListener {
            if (!updating) selectedId = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.node()?.id
        }
    }

    fun apply(root: ExplorerNode, expandSearch: Boolean = false) {
        lastVisitedNodes = 0
        if (!initialized) {
            tree.model = DefaultTreeModel(create(root))
            initialized = true
            shownTests = root.children.any { it.kind != ExplorerNodeKind.MESSAGE }
            TreeUtil.expand(tree, 4)
            return
        }
        val model = tree.model as DefaultTreeModel
        val swingRoot = model.root as DefaultMutableTreeNode
        val viewport = tree.parent as? JViewport
        val scroll = viewport?.viewPosition
        updating = true
        try {
            reconcile(model, swingRoot, root)
            if (!expandSearch) {
                tree.getExpandedDescendants(TreePath(swingRoot.path))?.asSequence()?.toList()?.asReversed()?.forEach { path ->
                    val id = (path.lastPathComponent as? DefaultMutableTreeNode)?.node()?.id
                    if (id !in expandedIds) tree.collapsePath(path)
                }
            }
            expandedIds.forEach { id -> byId[id]?.let { tree.expandPath(TreePath(it.path)) } }
            selectedId?.let { byId[it] }?.let { tree.selectionPath = TreePath(it.path) }
            if (expandSearch) TreeUtil.expandAll(tree)
            if (scroll != null) viewport.viewPosition = scroll
        } finally {
            updating = false
        }
        if (!shownTests && root.children.any { it.kind != ExplorerNodeKind.MESSAGE }) {
            shownTests = true
            TreeUtil.expand(tree, 4)
        }
    }

    private fun reconcile(model: DefaultTreeModel, swing: DefaultMutableTreeNode, incoming: ExplorerNode) {
        lastVisitedNodes++
        val old = swing.node()
        if (old === incoming) return
        swing.userObject = incoming
        if (old?.id != incoming.id) {
            old?.let { byId.remove(it.id) }
            byId[incoming.id] = swing
        }
        if (old == null || old.label != incoming.label || old.runnable != incoming.runnable || old.kind != incoming.kind) {
            model.nodeChanged(swing)
        }
        val expected = incoming.children.map { it.id }.toSet()
        for (index in swing.childCount - 1 downTo 0) {
            val child = swing.getChildAt(index) as DefaultMutableTreeNode
            if (child.node()?.id !in expected) {
                forget(child)
                model.removeNodeFromParent(child)
            }
        }
        incoming.children.forEachIndexed { index, child ->
            val existing = byId[child.id]?.takeIf { it.parent === swing }
            if (existing == null) model.insertNodeInto(create(child), swing, index) else {
                if (swing.getIndex(existing) != index) {
                    model.removeNodeFromParent(existing)
                    model.insertNodeInto(existing, swing, index)
                }
                reconcile(model, existing, child)
            }
        }
    }

    private fun create(node: ExplorerNode): DefaultMutableTreeNode = DefaultMutableTreeNode(node).apply {
        byId[node.id] = this
        node.children.forEach { add(create(it)) }
    }

    private fun forget(node: DefaultMutableTreeNode) {
        node.node()?.let { byId.remove(it.id) }
        node.children().asSequence().forEach { forget(it as DefaultMutableTreeNode) }
    }

    private fun DefaultMutableTreeNode.node() = userObject as? ExplorerNode
}
