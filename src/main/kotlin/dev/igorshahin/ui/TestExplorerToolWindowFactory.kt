package dev.igorshahin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

// Keep the native entry available during indexing; discovery itself waits for smart mode.
class TestExplorerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TestExplorerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.preferredFocusComponent()
        toolWindow.contentManager.addContent(content)
    }
}
