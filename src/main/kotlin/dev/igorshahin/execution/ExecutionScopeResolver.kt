package dev.igorshahin.execution

import dev.igorshahin.filter.TestVisibility
import dev.igorshahin.model.ExplorerNode

enum class ExecutionScopeState { FULL, PARTIAL, EMPTY }

data class ResolvedExecutionScope(
    val selected: ExplorerNode?,
    val state: ExecutionScopeState,
    val includedTests: List<ExplorerNode>,
    val visibleTestIds: Set<String>,
)

/** Uses the complete immutable model and persistent exclusions, never Swing rows/text search. */
class ExecutionScopeResolver {
    fun resolve(complete: ExplorerNode, selectedId: String, excluded: Set<String>): ResolvedExecutionScope {
        val selected = TestVisibility.find(complete, selectedId)
        val visibleIds = TestVisibility.leaves(TestVisibility.apply(complete, excluded)).map { it.id }.toSet()
        val all = selected?.let(TestVisibility::leaves).orEmpty()
        val included = all.filter { it.id in visibleIds }
        val state = when {
            included.isEmpty() -> ExecutionScopeState.EMPTY
            included.size == all.size -> ExecutionScopeState.FULL
            else -> ExecutionScopeState.PARTIAL
        }
        return ResolvedExecutionScope(selected, state, included, visibleIds)
    }
}
