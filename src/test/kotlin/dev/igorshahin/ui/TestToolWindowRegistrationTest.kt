package dev.igorshahin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TestToolWindowRegistrationTest : BasePlatformTestCase() {
    fun testNativeExtensionRemainsAvailableDuringIndexing() {
        val extension = ToolWindowEP.EP_NAME.extensionList.single { it.id == "Tests" }
        assertEquals("left", extension.anchor)
        assertEquals(TestExplorerToolWindowFactory::class.java.name, extension.factoryClass)
        assertTrue(extension.getToolWindowFactory(extension.pluginDescriptor) is DumbAware)
        assertTrue(extension.getToolWindowFactory(extension.pluginDescriptor).shouldBeAvailable(project))
    }

    fun testAdaptiveTestExplorerIconIsUsed() {
        val extension = ToolWindowEP.EP_NAME.extensionList.single { it.id == "Tests" }
        assertEquals("/icons/testExplorer.svg", extension.icon)
        val icon = IconLoader.getIcon(extension.icon, TestExplorerToolWindowFactory::class.java)
        assertTrue(icon.iconWidth > 0 && icon.iconHeight > 0)
        // Headless Platform tests replace loaded icons with placeholders. Pixel rendering is
        // checked by the separate sandbox smoke plugin, never by shipping production code.
    }
}
