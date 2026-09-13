package dev.igorshahin.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.filters.UrlFilter
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.lang.dart.ide.runner.DartConsoleFilter
import com.jetbrains.lang.dart.ide.runner.DartRelativePathsConsoleFilter
import com.jetbrains.lang.dart.util.DartUrlResolver
import io.flutter.pub.PubRoot
import io.flutter.run.FlutterDevice
import io.flutter.run.common.ConsoleProps
import io.flutter.run.daemon.DeviceService
import io.flutter.run.test.TestConfig
import io.flutter.sdk.FlutterSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class HotRestartProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "FlutterHotRestartTestRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == "Run" && profile is TestConfig && profile.getUserData(HotRestartRunData.KEY) != null

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? TestConfig
            ?: throw ExecutionException("Hot Restart runner requires a Flutter test configuration.")
        val spec = configuration.getUserData(HotRestartRunData.KEY)
            ?: throw ExecutionException("Hot Restart run specification is missing.")
        val launcher = HotRestartCommandLineState(environment, configuration, spec)
        val result = launcher.execute(environment.executor, this)
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}

private class HotRestartCommandLineState(
    environment: ExecutionEnvironment,
    private val configuration: TestConfig,
    private val spec: HotRestartRunSpec,
) : CommandLineState(environment) {
    private val targetFile = LocalFileSystem.getInstance().findFileByPath(spec.targetPath)
        ?: throw ExecutionException("Hot Restart target does not exist: ${spec.targetPath}")
    private val pubRoot = PubRoot.forFile(targetFile)
        ?: throw ExecutionException("Hot Restart target is outside a Flutter pub root: ${spec.targetPath}")

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(commandLine())
        handler.setShouldDestroyProcessRecursively(true)
        return handler
    }

    override fun createConsole(executor: Executor): ConsoleView {
        val project = environment.project
        val resolver = DartUrlResolver.getInstance(project, targetFile)
        val props = ConsoleProps.forPub(configuration, executor, resolver)
        val console: BaseTestsOutputConsoleView =
            SMTestRunnerConnectionUtil.createConsole(ConsoleProps.pubFrameworkName, props)
        ModuleUtil.findModuleForFile(targetFile, project)
        console.addMessageFilter(DartConsoleFilter(project, targetFile))
        console.addMessageFilter(DartRelativePathsConsoleFilter(project, pubRoot.root.path))
        console.addMessageFilter(UrlFilter())
        return console
    }

    private fun commandLine(): GeneralCommandLine {
        val project = environment.project
        val sdk = FlutterSdk.getFlutterSdk(project)
            ?: throw ExecutionException("The Flutter SDK is not configured.")
        val selectedDevice = DeviceService.getInstance(project).selectedDevice
        val (deviceId, flutterArguments) = DeviceArgument.extract(spec.flutterArguments, selectedDevice)
        val dartSdk = sdk.dartSdkPath ?: throw ExecutionException("The Dart SDK inside Flutter is unavailable.")
        val dart = Path.of(dartSdk, "bin", if (SystemInfo.isWindows) "dart.exe" else "dart").toString()
        val flutter = Path.of(sdk.homePath, "bin", if (SystemInfo.isWindows) "flutter.bat" else "flutter").toString()
        val arguments = HotRestartCommand.arguments(deviceId, spec.targetPath, flutter, spec.testIds, flutterArguments)
        return GeneralCommandLine(dart)
            .withWorkDirectory(pubRoot.root.path)
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(arguments)
    }
}

/** Command line of the `flutter_test_isolator` package executable in its IDE (`package:test --machine`) mode. */
internal object HotRestartCommand {
    const val ISOLATOR_PACKAGE = "flutter_test_isolator"
    private const val NATIVE_RESULTS_DEFINE = "INTEGRATION_TEST_SHOULD_REPORT_RESULTS_TO_NATIVE"

    /** The package does not disable native result reporting itself; `flutter run` targets need it off. */
    fun arguments(deviceId: String, targetPath: String, flutter: String, testIds: List<String>,
                  flutterArguments: List<String>): List<String> = buildList {
        addAll(listOf("run", ISOLATOR_PACKAGE, "--device", deviceId, "--target", targetPath, "--flutter", flutter,
            "--ide-protocol"))
        testIds.forEach { add("--test-id=$it") }
        add("--")
        addAll(flutterArguments)
        if (flutterArguments.none { NATIVE_RESULTS_DEFINE in it }) add("--dart-define=$NATIVE_RESULTS_DEFINE=false")
    }
}

internal object DeviceArgument {
    fun extract(arguments: List<String>, selectedDevice: FlutterDevice?): Pair<String, List<String>> {
        var device: String? = null
        val remaining = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            val inline = listOf("-d=", "--device=", "--device-id=").firstOrNull(argument::startsWith)
            when {
                inline != null -> device = accept(device, argument.removePrefix(inline))
                argument == "-d" || argument == "--device" || argument == "--device-id" -> {
                    if (++index >= arguments.size) throw ExecutionException("$argument requires a device ID.")
                    device = accept(device, arguments[index])
                }
                argument == "--machine" || argument == "-t" || argument == "--target" ||
                    argument.startsWith("--target=") ->
                    throw ExecutionException("$argument is owned by flutter_test_isolator.")
                else -> remaining += argument
            }
            index++
        }
        val resolved = device ?: selectedDevice?.deviceId()
            ?: throw ExecutionException("Select a Flutter device or pass --device in Test Explorer arguments.")
        return resolved to remaining
    }

    private fun accept(current: String?, next: String): String {
        if (next.isBlank()) throw ExecutionException("Flutter device ID cannot be empty.")
        if (current != null && current != next) {
            throw ExecutionException("Conflicting Flutter devices were requested: $current and $next.")
        }
        return next
    }
}
