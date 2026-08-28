package dev.igorshahin.execution

import com.intellij.execution.RunManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.XmlSerializer
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfiguration
import dev.igorshahin.model.TestRunTarget
import dev.igorshahin.model.TestRunTargetKind
import dev.igorshahin.settings.TestExplorerSettings
import io.flutter.run.test.FlutterTestConfigType
import io.flutter.run.test.TestConfig

class TestConfigurationFactoryTest : BasePlatformTestCase() {
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
        val arguments = "--dart-define=ENV=test\n--dart-define=\"LABEL=Test environment\" --timeout 30s"
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
