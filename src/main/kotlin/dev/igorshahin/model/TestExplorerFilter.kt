package dev.igorshahin.model

import java.util.Locale

class TestExplorerFilter {
    fun apply(root: ExplorerNode, query: String): ExplorerNode {
        val terms = parse(query)
        if (terms.includes.isEmpty() && terms.excludes.isEmpty()) return root
        return root.copy(
            children = root.children.mapNotNull { child ->
                filterNode(child, root.label.normalized(), terms, ancestorMatches = false)
            },
        )
    }

    private fun filterNode(
        node: ExplorerNode,
        parentPath: String,
        terms: Terms,
        ancestorMatches: Boolean,
    ): ExplorerNode? {
        val path = "$parentPath/${node.label.normalized()}"
        if (terms.excludes.any(path::contains)) return null

        val matchesHere = terms.includes.all(path::contains)
        val includeSubtree = ancestorMatches || matchesHere
        if (node.children.isEmpty()) return node.takeIf { includeSubtree }

        val children = node.children.mapNotNull { child ->
            filterNode(child, path, terms, includeSubtree)
        }
        return (if (children.size == node.children.size && children.indices.all { children[it] === node.children[it] })
            node else node.copy(children = children)).takeIf { children.isNotEmpty() }
    }

    private fun parse(query: String): Terms {
        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        query.split(WHITESPACE)
            .asSequence()
            .map { it.trim().trim(',') }
            .filter(String::isNotEmpty)
            .forEach { rawTerm ->
                val excluded = rawTerm.startsWith('!') || rawTerm.startsWith('-')
                val term = if (excluded) rawTerm.drop(1) else rawTerm
                if (term.isNotEmpty()) {
                    (if (excluded) excludes else includes) += term.normalized()
                }
            }
        return Terms(includes, excludes)
    }

    private fun String.normalized(): String = lowercase(Locale.ROOT)

    private data class Terms(
        val includes: List<String>,
        val excludes: List<String>,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
