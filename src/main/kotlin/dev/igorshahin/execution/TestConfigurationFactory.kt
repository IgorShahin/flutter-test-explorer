package dev.igorshahin.execution

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
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
    fun create(target: TestRunTarget, label: String, flutter: Boolean, arguments: String): RunnerAndConfigurationSettings {
        require(GlobalTestArguments.validationError(arguments) == null) { GlobalTestArguments.validationError(arguments).orEmpty() }
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
                additionalArgs = combined
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
