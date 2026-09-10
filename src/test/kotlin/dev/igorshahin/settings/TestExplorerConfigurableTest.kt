package dev.igorshahin.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.igorshahin.ui.TestArgumentsTableModel

class TestExplorerConfigurableTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.getService(TestExplorerSettings::class.java).loadState(TestExplorerSettings.State())
    }

    fun testFreshSettingsPageStartsEmptyAndUnmodified() {
        val configurable = TestExplorerConfigurable(project)
        try {
            configurable.createComponent()
            assertEquals(0, configurable.editorForTest()!!.model.rowCount)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testApplyPersistsOrderAndDisabledRowsThenBecomesUnmodified() {
        val settings = project.getService(TestExplorerSettings::class.java)
        val configurable = TestExplorerConfigurable(project)
        try {
            configurable.createComponent()
            val model = configurable.editorForTest()!!.model
            model.add(TestArgument("--first=one", true))
            model.add(TestArgument("--second=two words", false))
            model.add(TestArgument("--first=one", true))
            assertTrue(configurable.isModified)

            configurable.apply()

            assertEquals(listOf(
                TestArgument("--first=one", true),
                TestArgument("--second=two words", false),
                TestArgument("--first=one", true),
            ), settings.testArguments)
            assertEquals(listOf("--first=one", "--first=one"), settings.enabledArgumentValues)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testResetReloadsPersistedStateAndCancelDoesNotWriteChanges() {
        val settings = project.getService(TestExplorerSettings::class.java)
        settings.testArguments = listOf(TestArgument("--saved=value", false))
        val configurable = TestExplorerConfigurable(project)
        configurable.createComponent()
        val model = configurable.editorForTest()!!.model
        model.setValueAt("--unsaved=value", 0, TestArgumentsTableModel.ARGUMENT_COLUMN)
        model.setValueAt(true, 0, TestArgumentsTableModel.ENABLED_COLUMN)
        assertTrue(configurable.isModified)

        configurable.reset()
        assertEquals(listOf(TestArgument("--saved=value", false)), configurable.editorForTest()!!.arguments)
        assertFalse(configurable.isModified)

        model.setValueAt("--cancelled=value", 0, TestArgumentsTableModel.ARGUMENT_COLUMN)
        configurable.disposeUIResources()
        assertEquals(listOf(TestArgument("--saved=value", false)), settings.testArguments)
    }

    fun testApplyRejectsEmptyRowWithoutChangingPersistedState() {
        val settings = project.getService(TestExplorerSettings::class.java)
        val configurable = TestExplorerConfigurable(project)
        try {
            configurable.createComponent()
            configurable.editorForTest()!!.model.add(TestArgument())
            val failure = runCatching { configurable.apply() }.exceptionOrNull()
            assertTrue(failure is ConfigurationException)
            assertTrue(settings.testArguments.isEmpty())
        } finally {
            configurable.disposeUIResources()
        }
    }
}
