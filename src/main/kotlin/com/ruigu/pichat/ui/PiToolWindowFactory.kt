package com.ruigu.pichat.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates the native Pi Chat ToolWindow. The content is a JCEF browser, while
 * all Pi process and IntelliJ integration remains in Kotlin.
 */
class PiToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
        // 项目关闭 / ToolWindow 销毁时回收 pi 子进程
        Disposer.register(toolWindow.disposable, panel)
    }
}
