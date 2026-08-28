package dev.igorshahin.execution

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfiguration
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfigurationType
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunnerParameters
import dev.igorshahin.model.TestRunTarget
import dev.igorshahin.model.TestRunTargetKind
import io.flutter.run.test.FlutterTestConfigType
import io.flutter.run.test.TestConfig
import io.flutter.run.test.TestFields

/** Uses official configuration models; never edits an existing user configuration or a template. */
class TestConfigurationFactory(private val project: Project) {
    fun create(target: TestRunTarget, label: String, flutter: Boolean, arguments: String,
               nameFilter: String? = null): RunnerAndConfigurationSettings {
        require(GlobalTestArguments.validationError(arguments) == null) { GlobalTestArguments.validationError(arguments).orEmpty() }
        require(nameFilter == null || (target.kind == TestRunTargetKind.FILE && nameFilter.isNotEmpty() &&
            nameFilter.none { it.isWhitespace() || it == '"' || it == '\'' })) { "Invalid exact-name file filter" }
        val type = if (flutter) FlutterTestConfigType.getInstance() else DartTestRunConfigurationType.getInstance()
        val settings = RunManager.getInstance(project).createConfiguration("Tests: $label", type.configurationFactories.first())
        when (val configuration = settings.configuration) {
            is TestConfig -> {
                val originalArguments = configuration.fields.additionalArgs
                configuration.fields = when (target.kind) {
                    TestRunTargetKind.NAME -> TestFields.forTestName(requireNotNull(target.testName), target.fileOrDirectoryPath)
                    TestRunTargetKind.FILE -> TestFields.forFile(target.fileOrDirectoryPath)
                    TestRunTargetKind.DIRECTORY -> TestFields.forDir(target.fileOrDirectoryPath)
                }.apply { additionalArgs = originalArguments }
            }
            is DartTestRunConfiguration -> configuration.runnerParameters.apply {
                filePath = target.fileOrDirectoryPath
                testName = target.testName
                scope = when (target.kind) {
                    TestRunTargetKind.NAME -> DartTestRunnerParameters.Scope.GROUP_OR_TEST_BY_NAME
                    TestRunTargetKind.FILE -> DartTestRunnerParameters.Scope.FILE
                    TestRunTargetKind.DIRECTORY -> DartTestRunnerParameters.Scope.FOLDER
                }
            }
        }
        applyArguments(settings.configuration, arguments)
        if (nameFilter != null) when (val configuration = settings.configuration) {
            is TestConfig -> {
                // Flutter 95's useRegexp(true) escapes and anchors a SINGLE literal name in
                // FlutterSdk.flutterTest. Supply our union via the official additionalArgs field
                // of a FILE configuration instead. Only this adapter may add a target selector.
                configuration.fields = configuration.fields.copy().apply {
                    additionalArgs = GlobalTestArguments.merge(additionalArgs, "--name=$nameFilter")
                }
            }
            is DartTestRunConfiguration -> configuration.runnerParameters.apply {
                scope = DartTestRunnerParameters.Scope.MULTIPLE_NAMES
                testName = nameFilter // DartTestRunningState passes this as -n, without regexp escaping.
            }
        }
        settings.isTemporary = true
        return settings
    }

    private fun applyArguments(configuration: RunConfiguration, arguments: String) {
        when (configuration) {
            is TestConfig -> configuration.fields = configuration.fields.copy().apply {
                val combined = GlobalTestArguments.merge(additionalArgs, arguments)
                require(GlobalTestArguments.validationError(combined) == null) {
                    "The Flutter test template contains target selectors. Remove them from the template first."
                }
                // Flutter 95 uses String.split(" "), not IntelliJ's quote-aware parser.
                // Normalize separators but never split a single logical value into new CLI
                // arguments (which could introduce another path/name selector).
                val tokens = ParametersListUtil.parse(combined)
                require(tokens.none { token -> token.isEmpty() || token.any(Char::isWhitespace) }) {
                    "Flutter 95 cannot pass additional argument values containing spaces through its native test configuration. " +
                        "For such dart-define values, use --dart-define-from-file with a path without spaces. Nothing was started."
                }
                additionalArgs = tokens.joinToString(" ")
            }
            is DartTestRunConfiguration -> configuration.runnerParameters.apply {
                val combined = GlobalTestArguments.merge(testRunnerOptions, arguments)
                require(GlobalTestArguments.validationError(combined) == null) {
                    "The Dart test template contains target selectors. Remove them from the template first."
                }
                testRunnerOptions = combined
            }
            else -> error("Unsupported test configuration")
        }
    }
}
