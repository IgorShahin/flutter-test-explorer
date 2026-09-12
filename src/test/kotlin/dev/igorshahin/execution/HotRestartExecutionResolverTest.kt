package dev.igorshahin.execution

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.igorshahin.model.ExplorerNode
import dev.igorshahin.model.ExplorerNodeKind
import dev.igorshahin.model.SourceLocation
import dev.igorshahin.model.TestRunTarget
import dev.igorshahin.model.TestRunTargetKind
import java.nio.file.Files
import java.nio.file.Path

class HotRestartExecutionResolverTest : BasePlatformTestCase() {
    fun testCombinesSeveralKnownEntrypointsThroughOneAggregateTarget() {
        val base = Path.of(project.basePath!!)
        write(base.resolve("tool/hot_restart_runner/hot_restart_runner.dart"), "void main() {}")
        write(
            base.resolve("integration_test/hot_restart_all.dart"),
            "import 'features/a/a_test.dart';\nimport 'features/b/b_test.dart';",
        )
        val first = leaf(base, "integration_test/features/a/a_test.dart", "A")
        val second = leaf(base, "integration_test/features/b/b_test.dart", "B")
        val plan = TestExecutionPlan(includedTests = listOf(first, second))

        val execution = HotRestartExecutionResolver(project).resolve(plan)!!

        assertEquals(base.resolve("integration_test/hot_restart_all.dart").toString(), execution.targetPath)
        assertEquals(listOf(first.runTarget!!.hotRestartId, second.runTarget!!.hotRestartId), execution.testIds)
        assertEquals(listOf(first, second), execution.sources)
    }

    fun testUsesOriginalEntrypointForOneFileAndRejectsLegacyOrDynamicTests() {
        val base = Path.of(project.basePath!!)
        write(base.resolve("tool/hot_restart_runner/hot_restart_runner.dart"), "void main() {}")
        write(base.resolve("integration_test/hot_restart_all.dart"), "import 'features/a/a_test.dart';")
        val supported = leaf(base, "integration_test/features/a/a_test.dart", "A")
        assertEquals(
            supported.location!!.filePath,
            HotRestartExecutionResolver(project)
                .resolve(TestExecutionPlan(includedTests = listOf(supported)))!!
                .targetPath,
        )

        val legacyPath = base.resolve("integration_test/legacy_test.dart")
        write(legacyPath, "void main() {}")
        val legacy = supported.copy(location = SourceLocation(legacyPath.toString(), 1))
        assertNull(HotRestartExecutionResolver(project).resolve(TestExecutionPlan(includedTests = listOf(legacy))))
        val dynamic = supported.copy(runTarget = supported.runTarget!!.copy(hotRestartId = null))
        assertNull(HotRestartExecutionResolver(project).resolve(TestExecutionPlan(includedTests = listOf(dynamic))))
    }

    private fun leaf(base: Path, relative: String, name: String): ExplorerNode {
        val path = base.resolve(relative)
        write(path, "void main() => runAppTestEntrypoint(() {});")
        return ExplorerNode(
            kind = ExplorerNodeKind.TEST,
            label = name,
            location = SourceLocation(path.toString(), 1),
            id = "test:$name",
            runnable = true,
            runTarget = TestRunTarget(
                TestRunTargetKind.NAME,
                path.toString(),
                name,
                name,
                "$relative#suite#$name",
            ),
        )
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
