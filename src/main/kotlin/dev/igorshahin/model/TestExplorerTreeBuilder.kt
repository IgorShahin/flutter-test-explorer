package dev.igorshahin.model

import java.nio.file.Path

class TestExplorerTreeBuilder {
    fun build(files: List<DartTestFile>): ExplorerNode {
        val root = MutableNode(ExplorerNodeKind.ROOT, "Tests", id = TestNodeId.ROOT)

        files.sortedBy { it.relativePath }.forEach { file ->
            val testChildren = fromTestItems(file.children, file.relativePath, emptyList(), emptyList())
            if (testChildren.isEmpty()) return@forEach
            val segments = file.relativePath.split('/').filter(String::isNotBlank)
            if (segments.isEmpty()) return@forEach

            var parent = root
            segments.dropLast(1).forEachIndexed { index, directory ->
                val relativeDirectory = segments.take(index + 1).joinToString("/")
                val location = SourceLocation(
                    directoryPath(file.location.filePath, segments.size - index - 1),
                    0,
                )
                parent = parent.directory(
                    directory,
                    location,
                    relativeDirectory,
                )
            }
            parent.children += MutableNode(
                kind = ExplorerNodeKind.FILE,
                label = segments.last(),
                location = file.location,
                id = TestNodeId.file(file.relativePath),
                runnable = true,
                runTarget = TestRunTarget(TestRunTargetKind.FILE, file.location.filePath),
            ).apply {
                children += testChildren
            }
        }

        return root.freeze(sortFileSystemNodes = true).copy(runnable = root.children.isNotEmpty())
    }

    private fun directoryPath(filePath: String, parentCount: Int): String {
        var path = Path.of(filePath)
        repeat(parentCount) { path = path.parent }
        return path.toString()
    }

    private fun fromTestItems(
        items: List<DartTestItem>,
        relativeFilePath: String,
        parentNames: List<String>,
        parentIds: List<String>,
        parentNamesStatic: Boolean = true,
    ): List<MutableNode> {
        val occurrences = mutableMapOf<Pair<DartTestKind, String>, Int>()
        return items.mapNotNull { item ->
            val key = item.kind to item.name
            val occurrence = occurrences.getOrDefault(key, 0)
            occurrences[key] = occurrence + 1
            fromTestItem(item, relativeFilePath, parentNames, parentIds, occurrence, parentNamesStatic)
        }
    }

    private fun fromTestItem(
        item: DartTestItem,
        relativeFilePath: String,
        parentNames: List<String>,
        parentIds: List<String>,
        occurrence: Int,
        parentNamesStatic: Boolean,
    ): MutableNode? {
        val kind = when (item.kind) {
            DartTestKind.GROUP -> ExplorerNodeKind.GROUP
            DartTestKind.TEST -> ExplorerNodeKind.TEST
            DartTestKind.TEST_WIDGETS -> ExplorerNodeKind.TEST_WIDGETS
        }
        val logicalPath = parentNames + item.name
        val children = fromTestItems(item.children, relativeFilePath, logicalPath,
            parentIds + "${item.kind}:${item.name}@$occurrence", parentNamesStatic && item.runtimeNameKnown)
        if (item.kind == DartTestKind.GROUP && children.isEmpty()) return null
        if (item.kind != DartTestKind.GROUP && !item.runnable) return null
        return MutableNode(
            kind = kind,
            label = item.name,
            location = item.location,
            id = TestNodeId.test(relativeFilePath, kind, parentIds + item.name, occurrence),
            runnable = item.runnable,
            runTarget = item.takeIf { it.runnable }?.let {
                TestRunTarget(TestRunTargetKind.NAME, item.location.filePath, item.name,
                    logicalPath.joinToString(" ").takeIf { parentNamesStatic && item.runtimeNameKnown })
            },
        ).apply {
            this.children += children
        }
    }

    private class MutableNode(
        val kind: ExplorerNodeKind,
        val label: String,
        val location: SourceLocation? = null,
        val id: String,
        val runnable: Boolean = false,
        val runTarget: TestRunTarget? = null,
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        fun directory(
            label: String,
            location: SourceLocation,
            relativePath: String,
        ): MutableNode = children
            .firstOrNull { it.kind == ExplorerNodeKind.DIRECTORY && it.label == label }
            ?: MutableNode(
                ExplorerNodeKind.DIRECTORY,
                label,
                location,
                TestNodeId.directory(relativePath),
                runnable = true,
                runTarget = TestRunTarget(TestRunTargetKind.DIRECTORY, location.filePath),
            ).also(children::add)

        fun freeze(sortFileSystemNodes: Boolean = false): ExplorerNode {
            val frozenChildren = if (sortFileSystemNodes) {
                children.sortedWith(compareBy<MutableNode>({ nodeOrder(it.kind) }, { it.label.lowercase() }))
            } else {
                children
            }
            return ExplorerNode(
                kind = kind,
                label = label,
                location = location,
                children = frozenChildren.map {
                    it.freeze(sortFileSystemNodes = it.kind == ExplorerNodeKind.DIRECTORY)
                },
                id = id,
                runnable = runnable,
                runTarget = runTarget,
            )
        }

        private fun nodeOrder(kind: ExplorerNodeKind): Int = when (kind) {
            ExplorerNodeKind.DIRECTORY -> 0
            ExplorerNodeKind.FILE -> 1
            ExplorerNodeKind.GROUP,
            ExplorerNodeKind.TEST,
            ExplorerNodeKind.TEST_WIDGETS,
            ExplorerNodeKind.MESSAGE,
            ExplorerNodeKind.ROOT,
            -> 2
        }
    }
}
