package dev.igorshahin.execution

import com.intellij.execution.RunManager
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.execution.runners.ProgramRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.XmlSerializer
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunConfiguration
import com.jetbrains.lang.dart.ide.runner.test.DartTestRunnerParameters
import dev.igorshahin.model.TestRunTarget
import dev.igorshahin.model.TestRunTargetKind
import dev.igorshahin.settings.TestArgument
import dev.igorshahin.settings.TestExplorerSettings
import io.flutter.run.test.FlutterTestConfigType
import io.flutter.run.test.TestConfig

class TestConfigurationFactoryTest : BasePlatformTestCase() {
    fun testFreshSettingsContainNoDefaultArguments() {
        val settings = TestExplorerSettings()

        assertTrue(settings.testArguments.isEmpty())
        assertTrue(settings.enabledArgumentValues.isEmpty())
        assertTrue(settings.state.testArguments.isEmpty())
        assertNull(settings.state.globalArguments)
    }

    fun testPartialFlutterUsesFileAndOneNativeNameArgumentWithoutLosingGlobalArguments() {
        val filter = ExactTestNameFilter.create(listOf("suite A", "suite B.*"))
        val arguments = listOf("--dart-define=ENV=test", "--dart-define=COUNTRY=ru", "--timeout", "30s")
        val configuration = TestConfigurationFactory(project).create(
            TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"), "suite", true, arguments, filter,
        ).configuration as TestConfig
        assertEquals("/project/test/checks.dart", configuration.fields.testFile)
        assertNull(configuration.fields.testName)
        assertFalse(configuration.fields.useRegexp)
        assertEquals(arguments + "--name=$filter", flutterArguments(configuration))
    }

    fun testPartialDartUsesMultipleNamesRegexpAndKeepsRunnerOptions() {
        val filter = ExactTestNameFilter.create(listOf("suite A", "suite B"))
        val configuration = TestConfigurationFactory(project).create(
            TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"),
            "suite",
            false,
            listOf("--timeout", "30s"),
            filter,
        ).configuration as DartTestRunConfiguration
        assertEquals(DartTestRunnerParameters.Scope.MULTIPLE_NAMES, configuration.runnerParameters.scope)
        assertEquals(filter, configuration.runnerParameters.testName)
        assertEquals("/project/test/checks.dart", configuration.runnerParameters.filePath)
        assertEquals(listOf("--timeout", "30s"),
            ParametersListUtil.parse(configuration.runnerParameters.testRunnerOptions.orEmpty()))
    }

    fun testFilteredRunDoesNotChangeTemplateOrAllowGlobalSelectors() {
        val manager = RunManager.getInstance(project)
        val template = manager.getConfigurationTemplate(FlutterTestConfigType.getInstance().configurationFactories.first())
            .configuration as TestConfig
        val before = template.fields
        val filter = ExactTestNameFilter.create(listOf("suite A"))
        val factory = TestConfigurationFactory(project)
        factory.create(
            TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"),
            "suite",
            true,
            listOf("--timeout", "30s"),
            filter,
        )
        assertSame(before, template.fields)
        assertNull(template.fields.testName)
        assertTrue(runCatching {
            factory.create(
                TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart"),
                "suite",
                true,
                listOf("--name=hidden"),
                filter,
            )
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
        val arguments = listOf(
            "--dart-define=ENV=test",
            "--dart-define=LABEL=Test_environment",
            "--timeout",
            "30s",
        )
        val factory = TestConfigurationFactory(project)
        TestRunTargetKind.entries.forEach { kind ->
            val target = TestRunTarget(kind, "/project/test/auth_test.dart", "auth login")
            val config = factory.create(target, "selected", true, arguments).configuration as TestConfig
            assertTrue(config !== user.configuration)
            assertEquals(GlobalTestArguments.merge(oldTemplateArgs, arguments), flutterArguments(config))
        }
        assertEquals("--dart-define=USER=true", (user.configuration as TestConfig).fields.additionalArgs)
        assertEquals(oldTemplateArgs, template.fields.additionalArgs)
        assertEquals(allBefore, manager.allSettings)
    }

    fun testFlutterTransportPreservesEachLogicalArgumentIncludingWhitespaceAndQuotes() {
        val factory = TestConfigurationFactory(project)
        val target = TestRunTarget(TestRunTargetKind.FILE, "/project/test/checks.dart")
        val arguments = listOf(
            "--custom=two words",
            "--quoted=\"value\" and 'value'",
            "--unicode=кириллица 😀",
            "--separate-option",
            "separate value",
        )
        val config = factory.create(target, "suite", true, arguments).configuration as TestConfig
        assertEquals(arguments, flutterArguments(config))
        assertTrue(ParametersListUtil.parse(config.fields.additionalArgs.orEmpty()).all { it.none(Char::isWhitespace) })
    }

    fun testDartArgumentsUseExactTokensForNameFileAndDirectory() {
        val factory = TestConfigurationFactory(project)
        val arguments = listOf("--timeout", "30s", "--dart-define=LABEL=Two words")
        TestRunTargetKind.entries.forEach { kind ->
            val config = factory.create(
                TestRunTarget(kind, "/project/test/auth_test.dart", "auth"),
                "selected",
                false,
                arguments,
            ).configuration as DartTestRunConfiguration
            assertEquals(arguments, ParametersListUtil.parse(config.runnerParameters.testRunnerOptions.orEmpty()))
        }
    }

    fun testDisabledSettingsArgumentNeverEntersAnyNativeConfiguration() {
        val settings = TestExplorerSettings().apply {
            testArguments = listOf(
                TestArgument("--first=one", true),
                TestArgument("--second=two words", true),
                TestArgument("--disabled=three", false),
            )
        }
        val expected = listOf("--first=one", "--second=two words")
        assertEquals(expected, settings.enabledArgumentValues)

        TestRunTargetKind.entries.forEach { kind ->
            val target = TestRunTarget(kind, "/project/test/auth_test.dart", "auth")
            val flutter = TestConfigurationFactory(project).create(
                target, "selected", true, settings.enabledArgumentValues,
            ).configuration as TestConfig
            assertEquals(expected, flutterArguments(flutter))

            val dart = TestConfigurationFactory(project).create(
                target, "selected", false, settings.enabledArgumentValues,
            ).configuration as DartTestRunConfiguration
            assertEquals(expected, ParametersListUtil.parse(dart.runnerParameters.testRunnerOptions.orEmpty()))
        }
    }

    fun testStructuredSettingsPersistOrderEnabledStateDuplicatesAndDoNotAlias() {
        val original = TestExplorerSettings().apply {
            testArguments = listOf(
                TestArgument("--repeated=one", true),
                TestArgument("--temporarily-disabled=two", false),
                TestArgument("--repeated=one", true),
            )
            excludedNodeIds = setOf("file:test%2Fauth_test.dart", "test:stale")
        }
        val xml = XmlSerializer.serialize(original.state)
        assertFalse(xml.toString().contains("globalArguments"))
        val restored = TestExplorerSettings().apply {
            loadState(XmlSerializer.deserialize(xml, TestExplorerSettings.State::class.java))
        }
        assertEquals(original.testArguments, restored.testArguments)
        assertEquals(listOf("--repeated=one", "--repeated=one"), restored.enabledArgumentValues)
        assertEquals(original.excludedNodeIds, restored.excludedNodeIds)

        val snapshot = restored.state
        snapshot.testArguments.first().value = "changed"
        snapshot.excludedNodeIds.clear()
        assertEquals("--repeated=one", restored.testArguments.first().value)
        assertEquals(2, restored.excludedNodeIds.size)
        assertNull(restored.state.globalArguments)
    }

    fun testLegacyStringMigratesOnceUsingQuoteAwareParser() {
        val settings = TestExplorerSettings()
        settings.loadState(TestExplorerSettings.State(globalArguments = """
            --first=one
            --second="two words"
            --third=three
        """.trimIndent()))

        assertEquals(
            listOf(
                TestArgument("--first=one"),
                TestArgument("--second=two words"),
                TestArgument("--third=three"),
            ),
            settings.testArguments,
        )
        assertTrue(settings.testArguments.all(TestArgument::enabled))
        assertNull(settings.state.globalArguments)
    }

    fun testStructuredStateWinsOverLegacyField() {
        val settings = TestExplorerSettings()
        settings.loadState(TestExplorerSettings.State(
            globalArguments = "--dart-define=OLD=true",
            testArguments = mutableListOf(TestArgument("--dart-define=NEW=true", false)),
        ))
        assertEquals(listOf(TestArgument("--dart-define=NEW=true", false)), settings.testArguments)
        assertTrue(settings.enabledArgumentValues.isEmpty())
    }

    fun testLegacyParsingStructuredRenderingAndUnsafeTargetSelectors() {
        val legacy = "--first=one\n--second=\"two words\" --third three"
        val expected = listOf("--first=one", "--second=two words", "--third", "three")
        assertNull(GlobalTestArguments.validationError(legacy))
        assertNull(GlobalTestArguments.validationError(listOf("--custom=O'Reilly")))
        assertEquals(expected, GlobalTestArguments.parseLegacy(legacy))
        assertEquals(expected, ParametersListUtil.parse(GlobalTestArguments.renderForDart(expected)))
        listOf("--name hidden", "--plain-name=hidden", "-nhidden", "-- test/hidden_test.dart", "--custom=\"open")
            .forEach { assertNotNull(GlobalTestArguments.validationError(it)) }
        assertNotNull(GlobalTestArguments.validationError(listOf("")))
    }

    fun testFlutterArgumentTransportRestoresNestedMarkerAsOrdinaryUserValue() {
        val userValueThatLooksEncoded = FlutterArgumentTransport.encode("--ordinary=value")
        val native = FlutterArgumentTransport.encodeForNativeField(listOf(userValueThatLooksEncoded))

        assertEquals(
            listOf(userValueThatLooksEncoded),
            FlutterArgumentTransport.expand(ParametersListUtil.parse(native)),
        )
    }

    fun testFlutterArgumentTransportCustomizerIsRegistered() {
        assertTrue(CommandLineEnvCustomizer.EP_NAME.extensionList.any {
            it is FlutterTestArgumentCommandLineCustomizer
        })
    }

    fun testHotRestartConfigurationKeepsExactIdsAndLogicalFlutterArguments() {
        val execution = HotRestartExecution(
            targetPath = "/project/integration_test/hot_restart_all.dart",
            testIds = listOf("integration_test/a_test.dart#suite#A", "integration_test/b_test.dart#suite#B"),
            sources = emptyList(),
        )
        val arguments = listOf("--dart-define-from-file=env/test.json", "--dart-define=LABEL=two words")
        val configuration = TestConfigurationFactory(project)
            .createHotRestart(execution, "all", arguments)
            .configuration as TestConfig

        assertEquals(execution.targetPath, configuration.fields.testFile)
        assertNull(configuration.fields.testName)
        assertEquals(arguments, flutterArguments(configuration))
        assertEquals(
            HotRestartRunSpec(execution.targetPath, execution.testIds, arguments),
            configuration.getUserData(HotRestartRunData.KEY),
        )
    }

    fun testHotRestartProgramRunnerIsRegisteredBeforeExecution() {
        assertTrue(ProgramRunner.PROGRAM_RUNNER_EP.extensionList.any { it is HotRestartProgramRunner })
        val execution = HotRestartExecution(
            targetPath = "/project/integration_test/hot_restart_all.dart",
            testIds = listOf("integration_test/a_test.dart#suite#A"),
            sources = emptyList(),
        )
        val configuration = TestConfigurationFactory(project)
            .createHotRestart(execution, "all", emptyList())
            .configuration
        assertTrue(ProgramRunner.getRunner("Run", configuration) is HotRestartProgramRunner)
    }

    fun testDeviceArgumentUsesExplicitDeviceAndRemovesItFromFlutterArguments() {
        assertEquals(
            "windows" to listOf("--dart-define=ENV=test"),
            DeviceArgument.extract(listOf("--device-id=windows", "--dart-define=ENV=test"), null),
        )
        assertTrue(runCatching {
            DeviceArgument.extract(listOf("-d", "windows", "--device=linux"), null)
        }.exceptionOrNull() is com.intellij.execution.ExecutionException)
    }

    private fun flutterArguments(configuration: TestConfig): List<String> = FlutterArgumentTransport.expand(
        ParametersListUtil.parse(configuration.fields.additionalArgs.orEmpty()),
    )
}
