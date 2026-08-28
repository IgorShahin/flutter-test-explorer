package dev.igorshahin.model

/** Builds only changed file branches and copies their ancestors. Unchanged branches retain identity. */
internal class TestModelPatcher {
    private val builder = TestExplorerTreeBuilder()

    fun replace(root: ExplorerNode, replacements: Map<String, DartTestFile?>): ExplorerNode {
        if (replacements.isEmpty()) return root
        val changed = ChangedBranch()
        replacements.keys.forEach { path ->
            var branch = changed
            path.split('/').filter(String::isNotBlank).forEach { segment ->
                branch = branch.children.getOrPut(segment) { ChangedBranch() }
            }
        }
        return replaceBranch(root, builder.build(replacements.values.filterNotNull()), changed)
    }

    private fun replaceBranch(parent: ExplorerNode, newParent: ExplorerNode?, changed: ChangedBranch): ExplorerNode {
        val children = parent.children.associateByTo(linkedMapOf()) { it.label }
        val incomingChildren = newParent?.children.orEmpty().associateBy { it.label }
        var modified = false
        changed.children.forEach { (name, branch) ->
            val existing = children[name]
            val incoming = incomingChildren[name]
            val replacement = if (branch.children.isEmpty() || existing == null) incoming else
                replaceBranch(existing, incoming, branch).takeIf { it.children.isNotEmpty() }
            if (existing !== replacement) {
                modified = true
                if (replacement == null) children.remove(name) else children[name] = replacement
            }
        }
        if (!modified) return parent
        val sorted = children.values
            .sortedWith(compareBy({ if (it.kind == ExplorerNodeKind.DIRECTORY) 0 else 1 }, { it.label.lowercase() }))
        return parent.copy(children = sorted, runnable = sorted.isNotEmpty())
    }

    private class ChangedBranch(val children: MutableMap<String, ChangedBranch> = linkedMapOf())
}
