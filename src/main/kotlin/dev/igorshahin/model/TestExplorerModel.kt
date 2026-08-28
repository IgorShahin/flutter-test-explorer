package dev.igorshahin.model

enum class DartTestKind {
    GROUP,
    TEST,
    TEST_WIDGETS,
}

data class SourceLocation(
    val filePath: String,
    val offset: Int,
    val modificationStamp: Long? = null,
)

data class DartTestItem(
    val kind: DartTestKind,
    val name: String,
    val location: SourceLocation,
    val children: List<DartTestItem> = emptyList(),
    val runnable: Boolean = true,
    val nameIsStatic: Boolean = true,
)

data class DartTestFile(
    val relativePath: String,
    val location: SourceLocation,
    val children: List<DartTestItem>,
)

enum class ExplorerNodeKind {
    ROOT,
    DIRECTORY,
    FILE,
    GROUP,
    TEST,
    TEST_WIDGETS,
    MESSAGE,
}

enum class TestRunTargetKind {
    DIRECTORY,
    FILE,
    NAME,
}

data class TestRunTarget(
    val kind: TestRunTargetKind,
    val fileOrDirectoryPath: String,
    val testName: String? = null,
    val fullName: String? = testName,
)

data class ExplorerNode(
    val kind: ExplorerNodeKind,
    val label: String,
    val location: SourceLocation? = null,
    val children: List<ExplorerNode> = emptyList(),
    val id: String = "",
    val runnable: Boolean = false,
    val runTarget: TestRunTarget? = null,
)
