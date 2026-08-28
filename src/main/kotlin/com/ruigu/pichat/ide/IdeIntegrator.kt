package com.ruigu.pichat.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.ruigu.pichat.session.str
import com.google.gson.JsonParser
import java.io.File

/**
 * IDE 集成层：把 webview 的 open_file / show_diff 请求落到 IntelliJ
 * （编辑器打开定位、Diff 窗口对比）。与 ChatPanel 解耦，便于独立维护。
 *
 * @param project       当前项目
 * @param onErrorToast  错误提示回调（ChatPanel 转发为 webview addToast("error")）
 */
class IdeIntegrator(
    private val project: Project,
    private val onErrorToast: (String) -> Unit,
) {
    private val LOG = Logger.getInstance(IdeIntegrator::class.java)

    // ---- open_file 行号定位（预编译，路径解析每次前端 @/点击都会触发）----
    private val PATH_WITH_LINE = Regex("^(.*):(\\d+)(?:-(\\d+))?$")
    private val LINE_SUFFIX_NOISE = Regex(".*:\\d+$")

    /**
     * 处理 open_file：在 IDEA 编辑器中打开文件并定位到行（支持 path:line / path:line-start-end）。
     * 解析链：绝对路径 → 相对项目根 → 文件名模糊匹配（FilenameIndex）。
     */
    fun openFile(rawPath: String) {
        if (rawPath.isBlank() || project.isDisposed) return
        var path = rawPath
        var line = -1
        var endLine = -1
        PATH_WITH_LINE.matchEntire(rawPath)?.let { m ->
            val candidate = m.groupValues[1]
            // 排除 Windows 盘符/时间戳式误判（如 C:\... 或 xxx:42:13）
            if (candidate.isNotBlank() && !candidate.matches(LINE_SUFFIX_NOISE)) {
                path = candidate
                line = m.groupValues[2].toIntOrNull() ?: -1
                endLine = m.groupValues[3].toIntOrNull() ?: -1
            }
        }
        val vf = resolveFileForOpen(path)
        if (vf == null) {
            LOG.warn("[PiChatDiag] open_file 未找到: " + path)
            onErrorToast("无法打开文件：" + path)
            return
        }
        openInEditor(vf, line, endLine)
    }

    /**
     * 处理 show_diff：在 IDEA 中打开 Diff 窗口对比文件的修改前后内容。
     * 前端已传 oldContent/newContent（工具卡中的修改前后内容），无需读磁盘。
     * 对齐 cc-gui 的 SimpleDiffDisplayHandler：项目内路径校验 + DiffContentFactory 语法高亮。
     */
    fun showDiff(content: String) {
        val params = try {
            JsonParser.parseString(content).asJsonObject
        } catch (e: Exception) {
            LOG.warn("[PiChatDiag] show_diff 参数解析失败: " + e.message)
            return
        }
        val filePath = params.str("filePath")
        if (filePath.isBlank()) return
        val oldContent = params.str("oldContent")
        val newContent = params.str("newContent")
        val title = params.str("title")

        // 路径安全校验：仅允许项目内文件（防止前端传任意路径）
        val basePath = project.basePath
        if (basePath == null) {
            LOG.warn("[PiChatDiag] show_diff 拒绝：项目无 basePath")
            return
        }
        val normalizedPath = filePath.replace('\\', '/')
        val normalizedBase = basePath.replace('\\', '/').trimEnd('/')
        if (!normalizedPath.startsWith(normalizedBase, ignoreCase = true)) {
            LOG.warn("[PiChatDiag] show_diff 拒绝项目外路径: " + filePath)
            onErrorToast("无法显示 Diff（文件不在项目中）：" + filePath)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                val left = DiffContentFactory.getInstance().create(project, oldContent, fileType)
                val right = DiffContentFactory.getInstance().create(project, newContent, fileType)
                val diffTitle = if (title.isNotBlank()) title else "$fileName 修改对比"
                val request = SimpleDiffRequest(diffTitle, left, right, "修改前", "修改后")
                DiffManager.getInstance().showDiff(project, request)
            } catch (e: Exception) {
                LOG.error("[PiChatDiag] show_diff 打开失败: " + e.message, e)
            }
        }
    }

    private fun resolveFileForOpen(path: String): VirtualFile? {
        val direct = File(path)
        if (direct.exists()) {
            return LocalFileSystem.getInstance().findFileByIoFile(direct)
        }
        val basePath = project.basePath
        if (basePath != null) {
            val rel = File(basePath, path)
            if (rel.exists()) {
                return LocalFileSystem.getInstance().findFileByIoFile(rel)
            }
        }
        // 文件名模糊匹配（IDEA 索引，需要 read action）
        if (DumbService.isDumb(project)) return null
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return null
        val suffix = path.replace('\\', '/')
        val matches: Collection<VirtualFile> = ApplicationManager.getApplication().runReadAction(
            Computable { FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project)) }
        )
        if (matches.isEmpty()) return null
        matches.firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }?.let { return it }
        matches.firstOrNull { it.path.replace('\\', '/').contains(suffix) }?.let { return it }
        matches.firstOrNull { it.path.contains("/src/") || it.path.contains("\\src\\") }?.let { return it }
        return matches.first()
    }

    /** 在编辑器中打开文件，可选定位行号/选区。 */
    private fun openInEditor(vf: VirtualFile, line: Int, endLine: Int) {
        if (project.isDisposed || !vf.isValid) return
        if (line <= 0) {
            FileEditorManager.getInstance(project).openFile(vf, true)
            return
        }
        val descriptor = OpenFileDescriptor(project, vf)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor == null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
            return
        }
        val doc = editor.document
        val lineCount = doc.lineCount
        if (lineCount <= 0) return
        val zeroLine = line.coerceIn(1, lineCount) - 1
        val startOffset = doc.getLineStartOffset(zeroLine)
        editor.caretModel.moveToOffset(startOffset)
        if (endLine >= line) {
            val zeroEnd = endLine.coerceIn(1, lineCount) - 1
            editor.selectionModel.setSelection(startOffset, doc.getLineEndOffset(zeroEnd))
        } else {
            editor.selectionModel.removeSelection()
        }
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        editor.contentComponent.requestFocus()
    }
}
