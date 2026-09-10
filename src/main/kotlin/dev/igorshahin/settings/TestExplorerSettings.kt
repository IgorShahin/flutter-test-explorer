package dev.igorshahin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import dev.igorshahin.execution.GlobalTestArguments

data class TestArgument(
    var value: String = "",
    var enabled: Boolean = true,
)

@Service(Service.Level.PROJECT)
@State(name = "FlutterTestExplorerSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TestExplorerSettings : PersistentStateComponent<TestExplorerSettings.State> {
    data class State(
        /** Read only while migrating settings written before structured arguments were introduced. */
        var globalArguments: String? = null,
        var testArguments: MutableList<TestArgument> = mutableListOf(),
        var excludedNodeIds: MutableList<String> = mutableListOf(),
    )

    private var current = State()
    override fun getState(): State = current.copy(
        globalArguments = null,
        testArguments = copyArguments(current.testArguments),
        excludedNodeIds = current.excludedNodeIds.toMutableList(),
    )

    override fun loadState(state: State) {
        val arguments = if (state.testArguments.isNotEmpty()) {
            state.testArguments
        } else {
            GlobalTestArguments.parseLegacy(state.globalArguments.orEmpty()).map { TestArgument(it) }
        }
        current = State(
            globalArguments = null,
            testArguments = normalizeArguments(arguments),
            excludedNodeIds = state.excludedNodeIds.distinct().toMutableList(),
        )
    }

    var testArguments: List<TestArgument>
        get() = copyArguments(current.testArguments)
        set(value) { current = current.copy(globalArguments = null, testArguments = normalizeArguments(value)) }

    val enabledArgumentValues: List<String>
        get() = current.testArguments.asSequence()
            .filter(TestArgument::enabled)
            .map(TestArgument::value)
            .toList()

    var excludedNodeIds: Set<String>
        get() = current.excludedNodeIds.toSet()
        set(value) { current = current.copy(excludedNodeIds = value.sorted().toMutableList()) }

    private fun normalizeArguments(arguments: List<TestArgument>): MutableList<TestArgument> = arguments.mapNotNull { argument ->
        argument.value.trim().takeIf(String::isNotEmpty)?.let { TestArgument(it, argument.enabled) }
    }.toMutableList()

    private fun copyArguments(arguments: List<TestArgument>): MutableList<TestArgument> =
        arguments.map { TestArgument(it.value, it.enabled) }.toMutableList()
}
