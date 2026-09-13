package dev.igorshahin.execution

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.lang.dart.util.DartUrlResolver
import io.flutter.run.common.ConsoleProps
import io.flutter.run.test.TestConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in check for recorded `flutter_test_isolator --ide-protocol` stdout: replays it through the same Flutter console
 * properties and SM Test Runner model that back the Run tool window's Test Results. Set `HOT_RESTART_IDE_STREAMS` to a
 * directory with `*.stdout` files; the resulting trees are written to `test-results-tree.txt` there.
 */
class HotRestartTestResultsReplayTest : BasePlatformTestCase() {
    fun testRecordedIsolatorStreamsBuildNativeTestResults() {
        val directory = System.getenv(STREAMS_DIRECTORY)?.let(Path::of) ?: return
        val streams = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".stdout") }.sorted().toList()
        }
        val report = StringBuilder()
        streams.forEachIndexed { index, stream ->
            val root = replay(stream, index)
            report.appendLine("=== ${stream.fileName}: runs=1 suites=${root.children.size} final=${root.isFinal}")
            dump(root, 0, report)
        }
        Files.writeString(directory.resolve("test-results-tree.txt"), report.toString())
    }

    private fun replay(stream: Path, index: Int): SMTestProxy.SMRootTestProxy {
        val target = myFixture.addFileToProject("integration_test/replay_${index}_test.dart", "void main() {}").virtualFile
        val settings = TestConfigurationFactory(project)
            .createHotRestart(HotRestartExecution(target.path, listOf("replay"), emptyList()), "replay", emptyList())
        val properties = ConsoleProps.forPub(
            settings.configuration as TestConfig,
            DefaultRunExecutor.getRunExecutorInstance(),
            DartUrlResolver.getInstance(project, target),
        )
        val console = SMTestRunnerConnectionUtil.createConsole(ConsoleProps.pubFrameworkName, properties) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        val handler = NopProcessHandler()
        console.attachToProcess(handler)
        handler.startNotify()
        Files.readAllLines(stream).forEach { handler.notifyTextAvailable("$it\n", ProcessOutputTypes.STDOUT) }
        handler.destroyProcess()
        repeat(DISPATCH_ROUNDS) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
        return console.resultsViewer.testsRootNode
    }

    private fun dump(node: SMTestProxy, depth: Int, report: StringBuilder) {
        node.children.forEach { child ->
            val kind = if (child.isSuite) "suite" else "test"
            report.appendLine("${"  ".repeat(depth)}[$kind] ${child.name} | ${child.magnitudeInfo} | ${child.duration} ms")
            dump(child, depth + 1, report)
        }
    }

    private companion object {
        const val STREAMS_DIRECTORY = "HOT_RESTART_IDE_STREAMS"
        const val DISPATCH_ROUNDS = 20
    }
}
