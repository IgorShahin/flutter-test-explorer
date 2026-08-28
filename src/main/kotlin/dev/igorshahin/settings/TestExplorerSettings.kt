package dev.igorshahin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "FlutterTestExplorerSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TestExplorerSettings : PersistentStateComponent<TestExplorerSettings.State> {
    data class State(
        var globalArguments: String = "",
        var excludedNodeIds: MutableList<String> = mutableListOf(),
    )

    private var current = State()
    override fun getState(): State = current.copy(excludedNodeIds = current.excludedNodeIds.toMutableList())
    override fun loadState(state: State) {
        current = state.copy(excludedNodeIds = state.excludedNodeIds.distinct().toMutableList())
    }

    var globalArguments: String
        get() = current.globalArguments
        set(value) { current = current.copy(globalArguments = value) }

    var excludedNodeIds: Set<String>
        get() = current.excludedNodeIds.toSet()
        set(value) { current = current.copy(excludedNodeIds = value.sorted().toMutableList()) }
}
