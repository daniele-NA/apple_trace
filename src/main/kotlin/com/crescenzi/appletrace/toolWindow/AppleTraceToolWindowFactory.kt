package com.crescenzi.appletrace.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AppleTraceToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AppleTracePanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Plugin is macOS-only because it shells out to xcrun/idevicesyslog.
        return System.getProperty("os.name", "").lowercase().contains("mac")
    }
}
