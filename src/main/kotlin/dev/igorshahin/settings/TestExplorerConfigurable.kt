package dev.igorshahin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import dev.igorshahin.ui.TestArgumentsEditor
import javax.swing.JComponent

class TestExplorerConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
    private var editor: TestArgumentsEditor? = null

    override fun getDisplayName(): String = "Flutter Test Explorer"

    override fun createComponent(): JComponent = TestArgumentsEditor().also {
        editor = it
        it.replaceArguments(settings().testArguments)
    }

    override fun isModified(): Boolean {
        val currentEditor = editor ?: return false
        if (currentEditor.table.isEditing) return true
        return currentEditor.arguments != settings().testArguments
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val currentEditor = editor ?: return
        if (!currentEditor.stopEditing()) throw ConfigurationException("Finish editing the current argument first.")
        currentEditor.validationError()?.let { throw ConfigurationException(it) }
        settings().testArguments = currentEditor.arguments
        currentEditor.replaceArguments(settings().testArguments)
    }

    override fun reset() {
        editor?.replaceArguments(settings().testArguments)
    }

    override fun disposeUIResources() {
        editor?.dispose()
        editor = null
    }

    internal fun editorForTest(): TestArgumentsEditor? = editor

    private fun settings(): TestExplorerSettings = project.getService(TestExplorerSettings::class.java)
}
