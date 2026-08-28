package dev.igorshahin.execution

import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import dev.igorshahin.model.TestRunTargetKind

data class TestExecutionPlan(val targets: List<ExplorerNode> = emptyList(), val error: String? = null)

/** Never broadens a partially selected file/group to the official whole-file target. */
class TestExecutionPlanner {
    fun plan(complete: ExplorerNode, selectedId: String, excluded: Set<String>): TestExecutionPlan {
        val selected = TestVisibility.find(complete, selectedId)
            ?: return TestExecutionPlan(error = "This test no longer exists. Refresh the explorer.")
        val visibleRoot = TestVisibility.apply(complete, excluded)
        val visible = TestVisibility.find(visibleRoot, selectedId)
            ?: return TestExecutionPlan(error = "This test is excluded from the Test Explorer scope.")
        if (TestVisibility.leaves(visible).isEmpty()) return TestExecutionPlan(error = "No tests are included in this scope.")
        val visibleIds = TestVisibility.leaves(visibleRoot).map { it.id }.toSet()
        if (selected.kind == ExplorerNodeKind.ROOT || selected.kind == ExplorerNodeKind.DIRECTORY) {
            // Explicit files, not a broad folder: never include undiscovered or hidden files.
            val files = mutableListOf<ExplorerNode>()
            fun collect(node: ExplorerNode) {
                if (node.kind == ExplorerNodeKind.FILE) files += node else node.children.forEach(::collect)
            }
            collect(visible)
            val partial = files.any { file ->
                val original = TestVisibility.find(complete, file.id)!!
                TestVisibility.leaves(original).any { it.id !in visibleIds }
            }
            if (partial) return partialScope()
            return TestExecutionPlan(files)
        }
        if (!selected.runnable || selected.runTarget == null) {
            return TestExecutionPlan(error = "This is a structural node. Run one of its tests instead.")
        }
        if (TestVisibility.leaves(selected).any { it.id !in visibleIds }) return partialScope()
        if (selected.runTarget.kind == TestRunTargetKind.NAME) {
            val name = selected.runTarget.testName.orEmpty()
            // Gutter name targets use substring matching. A hidden same-name test elsewhere in
            // the file must not run as an accidental side effect of this apparently narrow run.
            val collision = TestVisibility.leaves(complete).any { test ->
                test.id !in visibleIds && test.location?.filePath == selected.location?.filePath &&
                    (test.runTarget?.fullName == null || test.runTarget.fullName.contains(name))
            }
            if (collision) return TestExecutionPlan(error =
                "The official name target also matches a hidden test in this file. Include it or use the IDE runner explicitly.")
        }
        return TestExecutionPlan(listOf(selected))
    }

    private fun partialScope() = TestExecutionPlan(error =
        "This scope contains hidden tests. A broad run could execute them. Run included tests individually, " +
            "or include the whole file/group in Visibility. Nothing was started.")
}
