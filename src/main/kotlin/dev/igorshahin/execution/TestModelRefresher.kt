package dev.igorshahin.execution

import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind

/** Stable request identity, never a retained PSI element or a stale source offset. */
data class TestRunScope(val selectedId: String, val kind: ExplorerNodeKind, val path: String?) {
    fun includes(filePath: String): Boolean = when (kind) {
        ExplorerNodeKind.ROOT -> true
        ExplorerNodeKind.DIRECTORY -> path != null && (filePath == path || filePath.startsWith(path.trimEnd('/') + "/"))
        else -> filePath == path
    }

    companion object {
        fun from(node: ExplorerNode) = TestRunScope(node.id, node.kind, node.location?.filePath)
    }
}

fun interface TestModelRefresher {
    /** Callback runs on EDT after committed, current per-file discovery (or a terminal failure). */
    fun ensureCurrent(scope: TestRunScope, ready: (Result<ExplorerNode>) -> Unit)
}
