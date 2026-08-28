package dev.igorshahin.discovery

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfiguration
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunnerParameters
import com.jetbrains.lang.dart.ide.runner.util.TestUtil
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartComponent
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestKind
import io.flutter.FlutterUtils
import io.flutter.run.test.FlutterTestConfigType
import io.flutter.run.test.TestConfig
import io.flutter.run.test.TestFields

internal interface TestRunnabilityValidator {
    fun prepare(files: List<DartFile>) {}
    fun kind(call: DartCallExpression): DartTestKind?
    fun isRunnable(call: DartCallExpression, name: String): Boolean
}

/** Fail closed: an arbitrary name configuration is NOT evidence that a call is a test. */
internal class IntelliJTestRunnabilityValidator(
    private val project: Project,
    private val outlines: FlutterTestOutlineIndex,
) : TestRunnabilityValidator {
    private var flutterCalls = emptyMap<String, Map<Int, DartTestKind>>()

    override fun prepare(files: List<DartFile>) {
        val flutterFiles = files.filter { FlutterUtils.isInFlutterProject(project, it) }
        com.intellij.openapi.diagnostic.Logger.getInstance(IntelliJTestRunnabilityValidator::class.java)
            .debug("Test Explorer validating ${flutterFiles.size} Flutter and ${files.size - flutterFiles.size} Dart entrypoints")
        flutterCalls = flutterFiles.associate {
            // Same file-context gate as Flutter's TestConfigUtils / gutter producer.
            val calls = if (FlutterUtils.isInTestDir(it)) outlines.testCalls(it) else emptyMap()
            com.intellij.openapi.diagnostic.Logger.getInstance(IntelliJTestRunnabilityValidator::class.java)
                .debug("Test Explorer analyzer recognized ${calls.size} test/group calls in ${it.name}")
            it.virtualFile.path to calls
        }
    }

    override fun kind(call: DartCallExpression): DartTestKind? {
        val path = call.containingFile.virtualFile.path
        if (path in flutterCalls) return flutterCalls[path]?.get(call.textOffset)
        // Dart's TestUtil checks only spelling. Resolve as an additional false-positive guard.
        val kind = when {
            TestUtil.isTest(call) -> DartTestKind.TEST
            TestUtil.isGroup(call) -> DartTestKind.GROUP
            else -> return null
        }
        val resolved = call.resolve() ?: return null
        val component = resolved as? DartComponent
            ?: PsiTreeUtil.getParentOfType(resolved, DartComponent::class.java, false) ?: return null
        val file = component.containingFile?.virtualFile ?: return null
        val packageRoot = generateSequence(file.parent) { it.parent }
            .firstOrNull { it.findChild("pubspec.yaml") != null } ?: return null
        val packageName = com.intellij.openapi.vfs.VfsUtilCore.loadText(packageRoot.findChild("pubspec.yaml")!!)
            .lineSequence().firstOrNull { it.startsWith("name:") }?.substringAfter(':')?.trim()
        return kind.takeIf { packageName == "test" || packageName == "test_api" }
    }

    override fun isRunnable(call: DartCallExpression, name: String): Boolean {
        val path = call.containingFile.virtualFile.path
        if (path in flutterCalls) {
            if (flutterCalls[path]?.containsKey(call.textOffset) != true) return false
            // Keep Flutter's own literal-name restrictions (including unsupported string forms).
            if (io.flutter.run.test.TestConfigUtils.getInstance().extractTestName(call) != name) return false
            // Exact call already recognized by the official outline. Same fields as the producer,
            // without its active-editor-only cache. Never used for an unrecognized call.
            val factory = FlutterTestConfigType.getInstance().configurationFactories.first()
            val config = RunManager.getInstance(project).createConfiguration("Validate test target", factory)
                .configuration as TestConfig
            config.fields = TestFields.forTestName(name, path)
            return try {
                config.checkConfiguration()
                true
            } catch (_: RuntimeConfigurationException) {
                false
            }
        }
        return ConfigurationContext(call).createConfigurationsFromContext().orEmpty().any {
            val config = it.configuration as? DartTestRunConfiguration ?: return@any false
            val parameters = config.runnerParameters
            if (parameters.scope != DartTestRunnerParameters.Scope.GROUP_OR_TEST_BY_NAME ||
                parameters.filePath != path || parameters.testName != name) return@any false
            try {
                config.checkConfiguration()
                true
            } catch (_: RuntimeConfigurationException) {
                false
            }
        }
    }
}
