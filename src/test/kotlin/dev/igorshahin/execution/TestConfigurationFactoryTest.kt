package dev.igorshahin.execution

import com.intellij.execution.RunManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.XmlSerializer
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfiguration
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunnerParameters
import dev.igorshahin.model.TestRunTarget
import dev.igorshahin.model.TestRunTargetKind
import dev.igorshahin.settings.TestExplorerSettings
import io.flutter.run.test.FlutterTestConfigType
import io.flutter.run.test.TestConfig

class TestConfigurationFactoryTest : BasePlatformTestCase() {
    fun testPartialFlutterUsesFileAndOneNativeNameArgumentWithoutLosingGlobalArguments() {
        val filter = ExactTestNameFilter.create(listOf("suite A", "suite B.*"))
        val arguments = "--dart-define=ENV=test --dart-define=COUNTRY=ru --timeout 30s"
        val configuration = TestConfigurationFactory(project).create(
            TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"), "suite", true, arguments, filter
        ).configuration as TestConfig
        assertEquals("/project/test/checks.dart", configuration.fields.testFile)
        assertNull(configuration.fields.testName)
        assertFalse(configuration.fields.useRegexp)
        assertEquals(ParametersListUtil.parse(arguments) + "--name=$filter",
            ParametersListUtil.parse(configuration.fields.additionalArgs.orEmpty()))
    }

    fun testPartialDartUsesMultipleNamesRegexpAndKeepsRunnerOptions() {
        val filter = ExactTestNameFilter.create(listOf("suite A", "suite B"))
        val configuration = TestConfigurationFactory(project).create(
            TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"), "suite", false, "--timeout 30s", filter
        ).configuration as DartTestRunConfiguration
        assertEquals(DartTestRunnerParameters.Scope.MULTIPLE_NAMES, configuration.runnerParameters.scope)
        assertEquals(filter, configuration.runnerParameters.testName)
        assertEquals("/project/test/checks.dart", configuration.runnerParameters.filePath)
        assertEquals("--timeout 30s", configuration.runnerParameters.testRunnerOptions)
    }

    fun testFilteredRunDoesNotChangeTemplateOrAllowGlobalSelectors() {
        val manager = RunManager.getInstance(project)
        val template = manager.getConfigurationTemplate(FlutterTestConfigType.getInstance().configurationFactories.first())
            .configuration as TestConfig
        val before = template.fields
        val filter = ExactTestNameFilter.create(listOf("suite A"))
        val factory = TestConfigurationFactory(project)
        factory.create(TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"), "suite", true, "--timeout 30s", filter)
        assertSame(before, template.fields)
        assertNull(template.fields.testName)
        assertTrue(runCatching {
            factory.create(TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"), "suite", true, "--name=hidden", filter)
        }.exceptionOrNull() is IllegalArgumentException)
    }
    fun testArgumentsApplyToEveryFlutterTargetWithoutMutatingUserConfiguration() {
        val manager = RunManager.getInstance(project)
        val flutterFactory = FlutterTestConfigType.getInstance().configurationFactories.first()
        val template = manager.getConfigurationTemplate(flutterFactory).configuration as TestConfig
        val oldTemplateArgs = template.fields.additionalArgs
        val user = manager.createConfiguration("User config", flutterFactory)
        (user.configuration as TestConfig).apply {
            fields = fields.copy().apply { additionalArgs = "--dart-define=USER=true" }
        }
        manager.addConfiguration(user)
        val allBefore = manager.allSettings.toList()
        val arguments = "--dart-define=ENV=test\n--dart-define=\"LABEL=Test_environment\" --timeout 30s"
        val factory = TestConfigurationFactory(project)
        TestRunTargetKind.entries.forEach { kind ->
            val target = TestRunTarget(kind, "/project/test/auth_test.dart", "auth login")
            val config = factory.create(target, "selected", true, arguments).configuration as TestConfig
            assertTrue(config !== user.configuration)
            assertEquals(ParametersListUtil.parse(GlobalTestArguments.merge(oldTemplateArgs, arguments)),
                ParametersListUtil.parse(config.fields.additionalArgs.orEmpty()))
        }
        assertEquals("--dart-define=USER=true", (user.configuration as TestConfig).fields.additionalArgs)
        assertEquals(oldTemplateArgs, template.fields.additionalArgs)
        assertEquals(allBefore, manager.allSettings)
    }

    fun testFlutterDoesNotSplitQuotedValuesIntoUnintendedNativeSelectors() {
        val factory = TestConfigurationFactory(project)
        val target = TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart")
        listOf("--dart-define=\"LABEL=Two words\"", "--dart-define=\"LABEL=ok --name=hidden\"",
            "--dart-define=\"LABEL=ok integration_test\"").forEach { arguments ->
            val failure = runCatching { factory.create(target, "suite", true, arguments,
                ExactTestNameFilter.create(listOf("suite A"))) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("Flutter 95"))
        }
        val config = factory.create(target, "suite", true, "--dart-define=ENV=test\n--timeout 30s")
            .configuration as TestConfig
        assertEquals("--dart-define=ENV=test --timeout 30s", config.fields.additionalArgs)
    }

    fun testDartArgumentsUseTestRunnerOptionsForNameFileAndDirectory() {
        val factory = TestConfigurationFactory(project)
        TestRunTargetKind.entries.forEach { kind ->
            val config = factory.create(TestRunTarget(kind, "/project/test/auth_test.dart", "auth"),
                "selected", false, "--timeout 30s").configuration as DartTestRunConfiguration
            assertEquals("--timeout 30s", config.runnerParameters.testRunnerOptions)
        }
    }

    fun testSettingsPersistThroughXmlRoundTripWithoutAliasing() {
        val original = TestExplorerSettings().apply {
            globalArguments = "--dart-define=ENV=test --dart-define=\"LABEL=Two words\""
            excludedNodeIds = setOf("file:test%2Fauth_test.dart", "test:stale")
        }
        val xml = XmlSerializer.serialize(original.state)
        val restored = TestExplorerSettings().apply {
            loadState(XmlSerializer.deserialize(xml, TestExplorerSettings.State::class.java))
        }
        assertEquals(original.globalArguments, restored.globalArguments)
        assertEquals(original.excludedNodeIds, restored.excludedNodeIds)
        val snapshot = restored.state
        snapshot.excludedNodeIds.clear()
        assertEquals(2, restored.excludedNodeIds.size)
    }

    fun testQuotingWhitespaceAndUnsafeTargetSelectors() {
        val args = "--dart-define=ENV=test\n--dart-define=\"LABEL=Two words\" --device-id macos"
        assertNull(GlobalTestArguments.validationError(args))
        assertNull(GlobalTestArguments.validationError("--dart-define=LABEL=O'Reilly"))
        assertEquals(listOf("--dart-define=ENV=test", "--dart-define=LABEL=Two words", "--device-id", "macos"),
            ParametersListUtil.parse(GlobalTestArguments.merge(null, args)))
        listOf("--name hidden", "--plain-name=hidden", "-nhidden", "-- test/hidden_test.dart", "--dart-define=\"open")
            .forEach { assertNotNull(GlobalTestArguments.validationError(it)) }
    }
}
