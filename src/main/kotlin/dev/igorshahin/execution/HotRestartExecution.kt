package dev.igorshahin.execution

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.igorshahin.model.ExplorerNode
import java.nio.file.Files
import java.nio.file.Path

data class HotRestartRunSpec(
    val targetPath: String,
    val testIds: List<String>,
    val flutterArguments: List<String>,
)

object HotRestartRunData {
    val KEY: Key<HotRestartRunSpec> = Key.create("flutter.test.explorer.hot.restart.run.spec")
}

data class HotRestartExecution(
    val targetPath: String,
    val testIds: List<String>,
    val sources: List<ExplorerNode>,
)

/** Activates only for entrypoints that already use the repository's Hot Restart-aware test framework. */
class HotRestartExecutionResolver(private val project: Project) {
    fun resolve(plan: TestExecutionPlan): HotRestartExecution? {
        val basePath = project.basePath?.let(Path::of) ?: return null
        val tests = plan.includedTests
        if (tests.isEmpty()) return null

        val runner = basePath.resolve(RUNNER_PATH)
        val aggregate = basePath.resolve(AGGREGATE_PATH)
        if (!Files.isRegularFile(runner) || !Files.isRegularFile(aggregate)) return null

        val sources = tests.distinctBy { it.location?.filePath }
        val sourcePaths = sources.map { source ->
            val absolute = source.location?.filePath?.let(Path::of) ?: return null
            if (!absolute.normalize().startsWith(basePath.normalize())) return null
            val relative = basePath.relativize(absolute).toString().replace('\\', '/')
            if (!relative.startsWith("integration_test/") || !Files.isRegularFile(absolute)) return null
            val content = Files.readString(absolute)
            if (ENTRYPOINT_MARKER !in content) return null
            relative to absolute
        }

        val ids = tests.map { it.runTarget?.hotRestartId ?: return null }
        if (ids.toSet().size != ids.size) return null

        val target = if (sourcePaths.size == 1) {
            sourcePaths.single().second
        } else {
            val aggregateContent = Files.readString(aggregate)
            if (sourcePaths.any { (relative, _) ->
                    val importPath = relative.removePrefix("integration_test/")
                    "'$importPath'" !in aggregateContent && "\"$importPath\"" !in aggregateContent
                }) return null
            aggregate
        }

        return HotRestartExecution(target.toString(), ids, sources)
    }

    private companion object {
        const val RUNNER_PATH = "tool/hot_restart_runner/hot_restart_runner.dart"
        const val AGGREGATE_PATH = "integration_test/hot_restart_all.dart"
        const val ENTRYPOINT_MARKER = "runAppTestEntrypoint"
    }
}
