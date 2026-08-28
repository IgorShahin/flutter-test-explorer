package dev.igorshahin.filter

import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind

enum class SelectionState { INCLUDED, EXCLUDED, PARTIAL }

object TestVisibility {
    fun apply(root: ExplorerNode, excluded: Set<String>): ExplorerNode =
        if (excluded.isEmpty()) root else prune(root, excluded) ?: root.copy(children = emptyList(), runnable = false)

    private fun prune(node: ExplorerNode, excluded: Set<String>): ExplorerNode? {
        if (node.id in excluded) return null
        if (node.children.isEmpty()) return node.takeIf { it.isTestLeaf && it.runnable }
        val children = node.children.mapNotNull { prune(it, excluded) }
        return (if (children.size == node.children.size && children.indices.all { children[it] === node.children[it] })
            node else node.copy(children = children)).takeIf { children.isNotEmpty() }
    }

    fun state(node: ExplorerNode, excluded: Set<String>): SelectionState {
        if (node.id in excluded) return SelectionState.EXCLUDED
        if (node.children.isEmpty()) return SelectionState.INCLUDED
        val states = node.children.map { state(it, excluded) }.toSet()
        return states.singleOrNull() ?: SelectionState.PARTIAL
    }

    fun leaves(node: ExplorerNode): List<ExplorerNode> =
        if (node.isTestLeaf && node.runnable) listOf(node) else node.children.flatMap(::leaves)

    fun find(node: ExplorerNode, id: String): ExplorerNode? =
        if (node.id == id) node else node.children.firstNotNullOfOrNull { find(it, id) }

    val ExplorerNode.isTestLeaf: Boolean
        get() = kind == ExplorerNodeKind.TEST || kind == ExplorerNodeKind.TEST_WIDGETS
}
