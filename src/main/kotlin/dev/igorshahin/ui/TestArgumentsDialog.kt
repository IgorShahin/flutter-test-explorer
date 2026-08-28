package dev.igorshahin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.igorshahin.execution.GlobalTestArguments
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class TestArgumentsDialog(project: Project, initial: String) : DialogWrapper(project) {
    private val editor = JBTextArea(initial, 9, 60)
    val arguments: String get() = editor.text

    init {
        title = "Test Explorer Settings"
        setOKButtonText("Apply")
        init()
    }
    override fun getPreferredFocusedComponent(): JComponent = editor
    override fun doValidate(): ValidationInfo? =
        GlobalTestArguments.validationError(arguments)?.let { ValidationInfo(it, editor) }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("Global test arguments — applied to every run from this panel:"), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(editor), BorderLayout.CENTER)
        add(JBLabel("<html>Example: --dart-define=ENV=test --dart-define=\"LABEL=Test environment\"<br>" +
            "Use spaces or newlines. Target/name selectors belong to the explorer, not this field.</html>"), BorderLayout.SOUTH)
    }
}
