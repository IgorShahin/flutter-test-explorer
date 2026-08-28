package dev.igorshahin.execution

import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import dev.igorshahin.model.TestRunTargetKind
import dev.igorshahin.model.TestRunTarget

data class PlannedTestExecution(val source: ExplorerNode, val target: TestRunTarget, val nameFilter: String? = null)

data class TestExecutionPlan(
    val targets: List<PlannedTestExecution> = emptyList(),
    val error: String? = null,
    val scopeState: ExecutionScopeState? = null,
)

/** Run All/directory/root orchestrate file plans, not a cross-entrypoint name target.
 * Each file independently uses native FILE for FULL, one exact filter for PARTIAL, or is
 * omitted for EMPTY. Several groups in a file still produce only one file execution.
 */
class TestExecutionPlanner {
    private val resolver = ExecutionScopeResolver()

    fun plan(complete: ExplorerNode, selectedId: String, excluded: Set<String>): TestExecutionPlan {
        val scope = resolver.resolve(complete, selectedId, excluded)
        val selected = scope.selected
            ?: return TestExecutionPlan(error = "This test no longer exists. Refresh the explorer.")
        if (scope.state == ExecutionScopeState.EMPTY) return TestExecutionPlan(
            error = "No visible tests to run", scopeState = scope.state)
        val allByFile = TestVisibility.leaves(complete).groupBy { it.location?.filePath }
        val targets = mutableListOf<PlannedTestExecution>()
        fun add(node: ExplorerNode): String? {
            val all = TestVisibility.leaves(node)
            val included = all.filter { it.id in scope.visibleTestIds }
            if (included.isEmpty()) return null
            val filePath = node.location?.filePath ?: return "Test source no longer exists. Refresh the explorer."
            val sameFile = allByFile[filePath].orEmpty()
            val target = node.runTarget
            val full = included.size == all.size
            // A native short-name target can overlap a hidden sibling outside the selected scope.
            val nativeOverlapsHidden = target?.kind == TestRunTargetKind.NAME && sameFile.any {
                it.id !in scope.visibleTestIds &&
                    (it.runTarget?.fullName == null || it.runTarget.fullName.contains(target.testName.orEmpty()))
            }
            if (full && node.runnable && target != null && !nativeOverlapsHidden) {
                targets += PlannedTestExecution(node, target)
                return null
            }
            val includedIds = included.map { it.id }.toSet()
            val other = sameFile.filter { it.id !in includedIds }
            if ((included + other).any { it.runTarget?.fullName == null }) return "Cannot safely filter this file: " +
                "a test's full runtime name is unknown (dynamic group or unsupported literal). " +
                    "Use static names or include the whole file. Nothing was started."
            val names = included.map { requireNotNull(it.runTarget?.fullName) }
            val nameSet = names.toSet()
            if (other.any { it.runTarget?.fullName in nameSet }) return "Cannot safely separate visible and excluded tests " +
                "with identical full names in this file. " +
                    "Give them distinct names or include all duplicates. Nothing was started."
            targets += PlannedTestExecution(node, TestRunTarget(TestRunTargetKind.FILE, filePath), ExactTestNameFilter.create(names))
            return null
        }
        val nodes = if (selected.kind == ExplorerNodeKind.ROOT || selected.kind == ExplorerNodeKind.DIRECTORY) {
            // The native models accept one file or one directory, not a file→filter mapping.
            // Retain explicit file batches so hidden files/other packages cannot be selected by a
            // cross-file name collision. Several tests in the SAME file still share one process.
            val files = mutableListOf<ExplorerNode>()
            fun collect(node: ExplorerNode) {
                if (node.kind == ExplorerNodeKind.FILE) files += node else node.children.forEach(::collect)
            }
            collect(selected)
            files
        } else listOf(selected)
        nodes.forEach { node ->
            add(node)?.let { return TestExecutionPlan(error = it, scopeState = scope.state) }
        }
        return TestExecutionPlan(targets, scopeState = scope.state)
    }
}
