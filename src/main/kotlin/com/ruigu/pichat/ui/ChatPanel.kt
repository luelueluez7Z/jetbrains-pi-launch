package com.ruigu.pichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.ruigu.pichat.rpc.ExtensionUiRequest
import com.ruigu.pichat.rpc.PiListener
import com.ruigu.pichat.rpc.RpcClient
import com.ruigu.pichat.rpc.RpcResponse
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.awt.BorderLayout
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Pi Chat 主面板（Compose 版）。
 * 通过 [RpcClient] 与本地 pi 会话通信（共享 ~/.pi/agent/sessions/）。
 * 所有 Compose 状态只在 EDT 上修改（PiListener 回调经 invokeLater 转发）。
 */
class ChatPanel(private val project: Project) : Disposable, PiListener {

    private val LOG = Logger.getInstance(ChatPanel::class.java)

    private data class ModelItem(val provider: String, val id: String, val name: String, val contextWindow: Long = 0)
    private data class SessionItem(
        val path: String,
        val name: String,
        val isCurrent: Boolean,
        val title: String? = null,
        val id: String = parseSessionId(name),
        val firstMessage: String = "",
        val messageCount: Int = 0,
        val lastTimestamp: Long = 0L,
    )
    private data class ExtensionDialogState(val request: ExtensionUiRequest)

    @Volatile
    private lateinit var client: RpcClient
    /** 单会话模式：同时只保留一个 pi 进程（与终端 pi 一致），切换会话即重启进程 */
    private val pendingSessionSwitch = IdentityHashMap<RpcClient, String>()
    private var streaming = false
    private var streamingAssistant: ChatMessage? = null

    // ================= Compose 状态（EDT） =================

    private val statusText = mutableStateOf("● 连接中…")
    private val statusTip = mutableStateOf("")
    private val queueCount = mutableStateOf(0)
    private val connected = mutableStateOf(false)
    private val messages = mutableStateListOf<ChatMessage>()
    private val isStreamingMsg = mutableStateOf(false)
    private val streamingText = mutableStateOf("")
    private val streamingThinking = mutableStateOf("")
    private val busy = mutableStateOf(false)
    private val inputText = mutableStateOf(TextFieldValue(""))
    private val sendHint = mutableStateOf("Enter 发送 · Shift+Enter 换行")
    // 发送新消息后请求滚动到底部（保证用户消息可见）
    private val scrollRequest = mutableStateOf(0)

    private val models = mutableStateListOf<ModelItem>()
    private val currentModel = mutableStateOf<ModelItem?>(null)
    private val thinkingLevels = mutableStateListOf<String>()
    private val currentThinking = mutableStateOf<String?>(null)
    private val sessions = mutableStateListOf<SessionItem>()
    private val currentSessionFile = mutableStateOf("")

    // 选择器弹窗状态
    private val showModelPicker = mutableStateOf(false)
    private val showThinkingPicker = mutableStateOf(false)
    private val showSessionPicker = mutableStateOf(false)
    private val showNewSessionDialog = mutableStateOf(false)
    private val extensionDialog = mutableStateOf<ExtensionDialogState?>(null)
    /** Webview AskUserQuestionDialog 的 requestId → pi extension_ui 请求映射。 */
    private val askUserByRequestId = HashMap<String, ExtensionUiRequest>()

    // The browser is intentionally kept as a single long-lived instance.  Pi Chat
    // streams lots of small updates and recreating a Chromium renderer whenever a
    // tool window is shown would be both slow and memory hungry.
    private val browser = JBCefBrowser()
    private val browserQuery = JBCefJSQuery.create(browser)
    private val browserPanel = JPanel(BorderLayout()).apply {
        add(browser.component, BorderLayout.CENTER)
    }
    private val gson = Gson()
    private var webUiReady = false

    private var webUpdateQueued = false
    private var webSequence = 0
    private var webStatusSent: String? = null

    private fun modelKey(model: ModelItem): String = "${model.provider}::${model.id}"

    private fun findModel(selection: String): ModelItem? =
        models.firstOrNull { modelKey(it) == selection }
            ?: models.firstOrNull { it.id == selection }

    /** The native ToolWindow component. The chat itself is rendered by JCEF. */
    val component: JComponent
        get() = browserPanel

    init {
        val cwd = if (project.basePath != null) Path.of(project.basePath) else Path.of(System.getProperty("user.home"))
        client = createClient(cwd)
        setupWebUi()
        startClient(client)
    }

    /** Adds a source-aware listener so inactive Pi processes can keep running without
     * leaking their events into the conversation currently shown in the webview. */
    private fun createClient(cwd: Path): RpcClient {
        val candidate = RpcClient(cwd)
        fun active(action: () -> Unit) {
            if (candidate === client) action()
        }
        candidate.addListener(object : PiListener {
            override fun onAgentStart() = active { this@ChatPanel.onAgentStart() }
            override fun onAgentEnd(messages: JsonArray?, willRetry: Boolean) = active { this@ChatPanel.onAgentEnd(messages, willRetry) }
            override fun onAgentSettled() = active { this@ChatPanel.onAgentSettled() }
            override fun onMessageUpdate(update: JsonObject) = active { this@ChatPanel.onMessageUpdate(update) }
            override fun onMessageEnd(message: JsonObject) = active { this@ChatPanel.onMessageEnd(message) }
            override fun onToolStart(toolCallId: String, toolName: String, args: JsonObject?) = active { this@ChatPanel.onToolStart(toolCallId, toolName, args) }
            override fun onToolUpdate(toolCallId: String, toolName: String, partialResult: JsonObject?) = active { this@ChatPanel.onToolUpdate(toolCallId, toolName, partialResult) }
            override fun onToolEnd(toolCallId: String, toolName: String, isError: Boolean, result: JsonObject?) = active { this@ChatPanel.onToolEnd(toolCallId, toolName, isError, result) }
            override fun onQueueUpdate(queue: JsonObject) = active { this@ChatPanel.onQueueUpdate(queue) }
            override fun onExtensionUi(req: ExtensionUiRequest) = active { this@ChatPanel.onExtensionUi(req) }
            override fun onProcessExit(exitCode: Int, stderrTail: String?) = active { this@ChatPanel.onProcessExit(exitCode, stderrTail) }
            override fun onError(message: String) = active { this@ChatPanel.onError(message) }
        })
        candidate.onStateReady { data ->
            onEdt {
                if (candidate !== client) return@onEdt
                val target = pendingSessionSwitch.remove(candidate)
                if (target != null) {
                    // 切换会话：跳过 handleStateReady 内的 loadHistory（异步），
                    // 避免它晚到后覆盖 switchSession 加载的目标会话历史。
                    handleStateReady(data, loadHistory = false)
                    candidate.switchSession(target).thenAccept { result ->
                        onEdt {
                            if (candidate !== client) return@onEdt
                            if (result != null && result.success()) {
                                clearMessages()
                                addSystem("已打开独立 Pi 会话")
                                refreshStatus()
                                loadHistory(force = true)
                                loadSessionList(target)
                            } else {
                                addSystem("打开会话失败: " + (result?.error() ?: "无响应"))
                            }
                        }
                    }
                } else {
                    // 正常打开（新建会话）：加载默认历史（通常为空）
                    handleStateReady(data)
                }
            }
        }
        return candidate
    }

    // ================= 生命周期 =================

    private fun setupWebUi() {
        browserQuery.addHandler { raw ->
            onEdt { handleWebAction(raw) }
            // JBCefJSQuery 契约：必须返回 Response，返回 null 会让 cefQuery
            // 查询失败（cc-gui 同样返回 Response("ok")）。
            JBCefJSQuery.Response("ok")
        }

        val html = javaClass.getResourceAsStream("/web/index.html")            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: "<html><body>Pi Chat UI resources were not found.</body></html>"
        val dark = com.intellij.util.ui.UIUtil.isUnderDarcula()
        // JBCefJSQuery 注入页面的全局查询函数名（cefQuery_<hash>_<index>）。
        // 把 sendToJava 直接嵌入 HTML 调用它——不再依赖 executeJavaScript 注入。
        val queryFunc = browserQuery.funcName
        val bootstrap = """
            <script>
              window.__INITIAL_IDE_THEME__ = '${if (dark) "dark" else "light"}';
              window.__INITIAL_TAB_PROVIDER__ = 'pi';
              window.__INITIAL_TAB_MODEL__ = '';
              // JS→Java bridge：直接调 JBCefJSQuery 注入页面的 cefQuery_* 函数。
              // 嵌入 HTML 而非 executeJavaScript 注入——remote JCEF 下 onLoadEnd
              // 回调可能丢失，注入不可靠（这是之前 bridge 完全不通的根因）。
              window.sendToJava = function(payload) {
                try {
                  window.$queryFunc({request: '' + JSON.stringify({type:'bridge',payload:String(payload)}), onSuccess: function(response) {}, onFailure: function(error_code, error_message) {}});
                } catch (e) {}
              };
            </script>
        """.trimIndent()
        // The bundled JavaScript itself contains a literal </head>. It appears before
        // the document's real closing tag, so neither a global replace nor replaceFirst
        // is safe: both can inject markup into the minified module and expose its
        // source as visible page text. Inject immediately before the final head tag.
        val headEnd = html.lastIndexOf("</head>")
        val htmlWithInitialState = if (headEnd >= 0) {
            html.substring(0, headEnd) + bootstrap + html.substring(headEnd)
        } else {
            html
        }
        // 用 file:// 临时文件加载（比 loadHTML 的 data: URL 更稳定）。
        // bridge 已直接嵌入 HTML（window.sendToJava 调 cefQuery_* 函数），
        // 不再需要 executeJavaScript 注入。
        val tmpHtml = java.io.File.createTempFile("pichat-", ".html")
        tmpHtml.writeText(htmlWithInitialState, StandardCharsets.UTF_8)
        tmpHtml.deleteOnExit()
        LOG.info("[PiChatDiag] loading file:// html size=" + htmlWithInitialState.length)
        browser.loadURL(tmpHtml.toURI().toString())
    }

    private fun handleWebAction(raw: String) {
        val action = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            return
        }
        val type = action.str("type")
        if (type != "heartbeat") {
            LOG.info("[PiChatDiag] webAction: " + type + (if (type == "bridge") " payload=" + action.str("payload").take(120) else ""))
        }
        when (type) {
            "bridge" -> handleReferenceBridge(action.str("payload"))
            "ready" -> {
                webUiReady = true
                publishWebState()
            }
            "send" -> sendMessage(action.str("text"))
            "abort" -> abort()
            "newSession" -> confirmNewSession()
            "selectModel" -> models.firstOrNull {
                it.provider == action.str("provider") &&
                    (it.id == action.str("id") || modelKey(it) == action.str("id"))
            }?.let { selectModel(it) }
            "selectThinking" -> action.str("level").takeIf { it.isNotEmpty() }?.let { selectThinking(it) }
            "selectSession" -> sessions.firstOrNull { it.path == action.str("path") }?.let { selectSession(it) }
            "extensionRespond" -> extensionDialog.value?.request?.let { request ->
                if (action.has("cancelled") && action.get("cancelled").asBoolean) {
                    cancelExtension(request)
                } else {
                    val value = action.str("value").takeIf { it.isNotEmpty() }
                    val confirmed = if (action.has("confirmed")) action.get("confirmed").asBoolean else null
                    completeExtension(request, value, confirmed)
                }
            }
        }
    }

    /** Maps the copied jetbrains-cc-gui Webview protocol onto the existing Pi RPC client. */
    private fun handleReferenceBridge(payload: String) {
        val separator = payload.indexOf(':')
        val event = if (separator >= 0) payload.substring(0, separator) else payload
        val content = if (separator >= 0) payload.substring(separator + 1) else ""
        when (event) {
            "frontend_ready" -> {
                LOG.info("[PiChatDiag] >>> frontend_ready 收到，webUiReady=true")
                webUiReady = true
                webStatusSent = null
                publishDependencyStatus()
                publishWebState()
                publishModels()
                publishCommands()
            }
            "heartbeat", "surface_damage_applied", "tab_loading_changed", "tab_status_changed",
            "history_dom_committed", "get_linkify_capabilities" -> Unit
            "get_dependency_status" -> publishDependencyStatus()
            "get_cli_models" -> if (content == "pi") publishModels()
            "send_message", "send_message_with_attachments" -> {
                val text = try {
                    JsonParser.parseString(content).asJsonObject.str("text")
                } catch (_: Exception) {
                    content
                }
                // 发送行为：steer = 打断引导（默认）；followUp = 排队等待当前对话完成
                val behavior = try {
                    JsonParser.parseString(content).asJsonObject.str("behavior").ifEmpty { "steer" }
                } catch (_: Exception) {
                    "steer"
                }
                // 图片附件：前端粘贴/拖拽的图片（base64），转发给 pi（RPC images 协议）
                val images = try {
                    parseImageAttachments(JsonParser.parseString(content).asJsonObject.get("attachments"))
                } catch (_: Exception) {
                    emptyList()
                }
                sendMessage(text, behavior, images)
            }
            "interrupt_session" -> abort()
            "create_new_session" -> confirmNewSession()
            "set_model" -> {
                val found = findModel(content)
                found?.let { selectModel(it) }
            }
            "set_reasoning_effort" -> if (thinkingLevels.contains(content)) selectThinking(content)
            "set_provider" -> {
                if (content != "pi") callWeb("addToast", "当前插件仅启用 Pi 后端", "info")
            }
            "get_active_provider" -> callWeb("updateActiveProvider", gson.toJson(mapOf("id" to "pi")))
            "get_mode" -> callWeb("onModeReceived", "default")
            "get_thinking_enabled" -> callWeb("updateThinkingEnabled", gson.toJson(mapOf("enabled" to true)))
            "get_selected_agent" -> callWeb("onSelectedAgentReceived", "")
            "refresh_slash_commands" -> publishCommands()
            "list_files" -> handleListFiles(content)
            "get_agents" -> callWeb("updateAgents", "[]")
            "get_prompts" -> callWeb("updatePrompts", "[]")
            "load_history_data", "deep_search_history" -> publishHistoryData()
            "ask_user_question_response" -> handleAskUserResponse(content)
            "load_session" -> {
                val id = try {
                    JsonParser.parseString(content).asJsonObject.str("sessionId")
                } catch (_: Exception) {
                    content
                }
                LOG.info("[PiChatDiag] load_session id=" + id + " sessions=" + sessions.size)
                val match = sessions.firstOrNull { it.path.equals(id, ignoreCase = true) }
                LOG.info("[PiChatDiag] load_session match=" + (match?.path ?: "null"))
                match?.let { selectSession(it) }
            }
            "load_subagent_session" -> handleLoadSubagentSession(content)
            "delete_session" -> deleteSession(content)
            "delete_sessions" -> deleteSessions(content)
            "update_title" -> updateSessionTitle(content)
            "open_file" -> handleOpenFile(content)
            "show_diff" -> handleShowDiff(content)
            "get_context_presets" -> publishContextPresets()
            "set_context_preset" -> handleSetContextPreset(content)
            "set_plan_mode" -> handleSetPlanMode(content)
            "compact_session" -> handleCompactSession()
            "enhance_prompt" -> handleEnhancePrompt(content)
            "get_optimize_settings" -> publishOptimizeSettings()
            "set_optimize_settings" -> handleSetOptimizeSettings(content)
            "export_session", "toggle_favorite",
            "convert_to_cli_session", "create_new_tab" ->
                callWeb("addToast", "该交互将在下一阶段接入 Pi", "info")
        }
    }

    private fun publishDependencyStatus() {
        callWeb("updateDependencyStatus", "{}")
    }

    /**
     * 处理 open_file：在 IDEA 编辑器中打开文件并定位到行（支持 path:line / path:line-start-end）。
     * 解析链：绝对路径 → 相对项目根 → 文件名模糊匹配（FilenameIndex）。
     */
    private fun handleOpenFile(rawPath: String) {
        if (rawPath.isBlank() || project.isDisposed) return
        val linePattern = Regex("^(.*):(\\d+)(?:-(\\d+))?$")
        var path = rawPath
        var line = -1
        var endLine = -1
        linePattern.matchEntire(rawPath)?.let { m ->
            val candidate = m.groupValues[1]
            // 排除 Windows 盘符/时间戳式误判（如 C:\... 或 xxx:42:13）
            if (candidate.isNotBlank() && !candidate.matches(Regex(".*:\\d+$"))) {
                path = candidate
                line = m.groupValues[2].toIntOrNull() ?: -1
                endLine = m.groupValues[3].toIntOrNull() ?: -1
            }
        }
        val vf = resolveFileForOpen(path)
        if (vf == null) {
            LOG.warn("[PiChatDiag] open_file 未找到: " + path)
            callWeb("addToast", "无法打开文件：" + path, "error")
            return
        }
        openInEditor(vf, line, endLine)
    }

    /**
     * 处理 show_diff：在 IDEA 中打开 Diff 窗口对比文件的修改前后内容。
     * 前端已传 oldContent/newContent（工具卡中的修改前后内容），无需读磁盘。
     * 对齐 cc-gui 的 SimpleDiffDisplayHandler：项目内路径校验 + DiffContentFactory 语法高亮。
     */
    private fun handleShowDiff(content: String) {
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
            callWeb("addToast", "无法显示 Diff（文件不在项目中）：" + filePath, "error")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
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

    /**
     * 处理 load_subagent_session：从 tool 执行时保留的 details（subagent 工具的
     * details.results[].messages）提取子代理的完整消息，转成前端 SubagentProcessDetails
     * 可解析的格式（{type, raw:{content:[tool_use 块]}}）后回传。
     */
    private fun handleLoadSubagentSession(content: String) {
        val params = try {
            JsonParser.parseString(content).asJsonObject
        } catch (_: Exception) {
            return
        }
        val rawToolUseId = params.str("toolUseId") ?: params.str("agentId") ?: return
        // 前端并行/链式可能带 ::idx 后缀，回退到纯 toolCallId 定位
        val toolUseId = rawToolUseId.substringBefore("::")
        onEdt {
            val m = findToolMessage(toolUseId)
            val details = m?.toolResultDetails
            if (m == null || details == null || !details.isJsonObject) {
                callWeb(
                    "onSubagentHistoryLoaded",
                    gson.toJson(mapOf(
                        "success" to false,
                        "toolUseId" to rawToolUseId,
                        "error" to "未找到子代理执行记录"
                    ))
                )
                return@onEdt
            }

            val detailObj = details.asJsonObject
            val results = detailObj.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
            val frontendMessages = JsonArray()
            if (results != null) {
                for (r in results) {
                    if (!r.isJsonObject) continue
                    val rm = r.asJsonObject
                    val msgs = rm.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    for (msg in msgs) {
                        if (!msg.isJsonObject) continue
                        val mm = msg.asJsonObject
                        val role = mm.str("role")
                        val content = mm.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                        val converted = JsonObject().apply {
                            addProperty("type", if (role == "assistant") "assistant" else "user")
                        }
                        val blocks = JsonArray()
                        if (content != null) {
                            for (b in content) {
                                if (!b.isJsonObject) continue
                                val block = b.asJsonObject
                                when (block.str("type")) {
                                    "toolCall" -> blocks.add(JsonObject().apply {
                                        addProperty("type", "tool_use")
                                        addProperty("id", block.str("id") ?: "tool-${blocks.size()}")
                                        addProperty("name", block.str("name") ?: "tool")
                                        val args = block.get("arguments")
                                        if (args != null && args.isJsonObject) add("input", args)
                                        else add("input", JsonObject())
                                    })
                                    // text 块作为最终输出展示（resultText），这里不逐块列出
                                    else -> Unit
                                }
                            }
                        }
                        if (blocks.size() > 0) {
                            converted.add("raw", JsonObject().apply { add("content", blocks) })
                            frontendMessages.add(converted)
                        }
                    }
                }
            }

            // 是否全部结束：results 均存在且 exitCode != -1
            val completed = results == null || results.all { r ->
                r.isJsonObject && (r.asJsonObject.get("exitCode")?.asInt ?: 0) != -1
            }
            val status = if (completed) "completed" else "running"

            val payload = JsonObject().apply {
                addProperty("success", true)
                addProperty("toolUseId", rawToolUseId)
                addProperty("status", status)
                addProperty("completed", completed)
                add("messages", frontendMessages)
            }
            callWeb("onSubagentHistoryLoaded", gson.toJson(payload))
        }
    }

    private fun publishModels() {
        if (!webUiReady) return
        val modelRows = JsonArray().also { rows ->
            models.forEach { model ->
                rows.add(JsonObject().apply {
                    // id 用纯模型 id，前端 useCliModels 会拼成 provider::id（两段），
                    // 与后端 modelKey / findModel 匹配，避免重复拼 provider 变成三段
                    addProperty("id", model.id)
                    // 前端 ModelSelect 会自行拼接 provider / name，这里只给纯名字，避免重复
                    addProperty("label", model.name)
                    addProperty("description", model.id)
                    addProperty("provider", model.provider)
                })
            }
        }
        val payload = JsonObject().apply {
            addProperty("provider", "pi")
            addProperty("success", true)
            add("models", modelRows)
            currentModel.value?.let { addProperty("defaultModel", modelKey(it)) }
        }
        callWeb("setCliModels", gson.toJson(payload))
    }

    /**
     * Pushes Pi's registered slash commands (extension commands + skills) to the
     * webview's completion dropdown. The payload mirrors the SDK shape the
     * frontend already parses: [{name, description, source}].
     */
    private fun publishCommands() {
        if (!webUiReady) return
        client.getCommands().whenComplete { res, err ->
            onEdt {
                if (!webUiReady) return@onEdt
                val rows = JsonArray()
                if (err == null && res != null && res.success() && res.data() != null && res.data().has("commands")) {
                    val arr = res.data().getAsJsonArray("commands")
                    for (el in arr) {
                        if (!el.isJsonObject) continue
                        val cmd = el.asJsonObject
                        val name = cmd.str("name")
                        if (name.isBlank()) continue
                        rows.add(JsonObject().apply {
                            addProperty("name", name)
                            addProperty("description", cmd.str("description"))
                            addProperty("source", cmd.str("source"))
                        })
                    }
                }
                callWeb("updateSlashCommands", gson.toJson(rows))
            }
        }
    }

    // ================= @ 文件补全（list_files） =================

    private data class FileEntry(
        val name: String,
        val relPath: String,
        val abs: String,
        val type: String,
        val ext: String
    )

    private val fileScanExcluded = setOf(
        ".git", ".idea", ".gradle", ".kotlin", ".intellijPlatform", "node_modules",
        "build", "out", "target", "dist", ".venv", "venv", "__pycache__", ".next", ".turbo",
        "coverage", "AppData", "Application Data", ".cache", ".npm", ".local", "Library"
    )
    private val fileCacheLock = Any()
    private val fileListCache = ArrayList<FileEntry>()

    @Volatile private var fileListCacheKey: String? = null
    @Volatile private var fileListCacheTime = 0L

    private companion object {
        const val FILE_CACHE_TTL_MS = 60_000L
        const val FILE_SCAN_MAX_DEPTH = 8
        const val FILE_SCAN_MAX_ITEMS = 1500
    }

    /** 扫描项目文件树（排除生成/依赖目录），结果带 TTL 缓存。 */
    private fun scanProjectFiles(root: Path): List<FileEntry> {
        synchronized(fileCacheLock) {
            val now = System.currentTimeMillis()
            if (fileListCacheKey == root.toString() && now - fileListCacheTime < FILE_CACHE_TTL_MS) {
                return ArrayList(fileListCache)
            }
            val entries = ArrayList<FileEntry>()
            scanDir(root, root, 0, entries)
            fileListCache.clear()
            fileListCache.addAll(entries)
            fileListCacheKey = root.toString()
            fileListCacheTime = now
            return entries
        }
    }

    private fun scanDir(root: Path, dir: Path, depth: Int, out: MutableList<FileEntry>) {
        if (depth > FILE_SCAN_MAX_DEPTH || out.size >= FILE_SCAN_MAX_ITEMS) return
        val stream = try {
            Files.list(dir)
        } catch (e: Exception) {
            return
        }
        stream.use { s ->
            val items = try {
                s.sorted(Comparator.comparing { p: Path -> p.fileName.toString().lowercase() }).toList()
            } catch (e: Exception) {
                return
            }
            for (p in items) {
                if (out.size >= FILE_SCAN_MAX_ITEMS) break
                val name = p.fileName.toString()
                if (name == ".gitignore" || name == ".env" || name == ".npmrc" || name == ".editorconfig") {
                    out.add(fileEntry(root, p, name, isDir = false))
                    continue
                }
                val isDir = Files.isDirectory(p)
                if (name.startsWith(".")) continue // 其余隐藏文件/目录跳过
                if (isDir && name in fileScanExcluded) continue
                out.add(fileEntry(root, p, name, isDir))
                if (isDir) scanDir(root, p, depth + 1, out)
            }
        }
    }

    private fun fileEntry(root: Path, p: Path, name: String, isDir: Boolean): FileEntry {
        val rel = root.relativize(p).toString().replace('\\', '/')
        val ext = if (isDir) "" else name.substringAfterLast('.', "").takeIf { it != name } ?: ""
        return FileEntry(name, rel, p.toString(), if (isDir) "directory" else "file", ext)
    }

    /**
     * 响应前端 @ 引用的 list_files 请求。按 currentPath 过滤项目文件树，
     * 带 requestId 回传（前端据此丢弃过期响应）。扫描在后台线程执行。
     */
    private fun handleListFiles(payload: String) {
        val req = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (_: Exception) {
            return
        }
        val requestId = if (req.has("requestId") && !req.get("requestId").isJsonNull) req.get("requestId").asString else ""
        val currentPath = req.str("currentPath").trim('/')
        // 仅在真实项目目录下提供文件补全：未打开项目时返回空，
        // 避免误扫用户主目录（AppData 等系统目录产生大量噪音）。
        val basePath = project.basePath
        if (basePath.isNullOrBlank() || !Files.isDirectory(Path.of(basePath))) return
        val base = Path.of(basePath).normalize()
        if (!Files.isDirectory(base)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = scanProjectFiles(base)
            val start = if (currentPath.isEmpty()) base else base.resolve(currentPath).normalize()
            val inRange = start.startsWith(base) && Files.isDirectory(start)
            val rows = JsonArray()
            if (inRange) {
                for (e in entries) {
                    val abs = Path.of(e.abs).normalize()
                    if (abs.startsWith(start) && abs != start) {
                        rows.add(JsonObject().apply {
                            addProperty("name", e.name)
                            addProperty("path", e.relPath)
                            addProperty("absolutePath", e.abs)
                            addProperty("type", e.type)
                            if (e.ext.isNotEmpty()) addProperty("extension", e.ext)
                        })
                    }
                }
            }
            val result = JsonObject().apply {
                add("files", rows)
                addProperty("requestId", requestId)
            }
            onEdt { callWeb("onFileListResult", gson.toJson(result)) }
        }
    }

    private fun publishHistoryData() {
        if (!webUiReady) return
        val rows = JsonArray().also { result ->
            sessions.forEach { session ->
                result.add(JsonObject().apply {
                    addProperty("sessionId", session.path)
                    addProperty("id", session.id)
                    // TUI 规则：有名称显示名称，无名称显示首条 user 消息摘要，再回退文件名
                    addProperty("title", session.title ?: session.firstMessage.ifBlank { session.name.removeSuffix(".jsonl") })
                    addProperty("messageCount", if (session.isCurrent) messages.size else session.messageCount)
                    if (session.lastTimestamp > 0) addProperty("lastTimestamp", java.time.Instant.ofEpochMilli(session.lastTimestamp).toString())
                    addProperty("provider", "pi")
                    currentModel.value?.let { addProperty("model", it.id) }
                })
            }
        }
        callWeb("setHistoryData", JsonObject().apply {
            addProperty("success", true)
            add("sessions", rows)
            addProperty("total", rows.size())
        })
    }

    /** Coalesces bursty Pi streaming events into a single browser update per EDT turn. */
    private fun publishWebState() {
        if (!webUiReady || webUpdateQueued) return
        webUpdateQueued = true
        ApplicationManager.getApplication().invokeLater {
            webUpdateQueued = false
            if (!webUiReady || browser.isDisposed) return@invokeLater
            val snapshot = webMessages()
            callWeb("updateMessages", gson.toJson(snapshot), ++webSequence)
            val status = statusText.value.removePrefix("● ")
            if (status != webStatusSent) {
                webStatusSent = status
                callWeb("updateStatus", status)
            }
            callWeb("showLoading", busy.value)
            if (currentSessionFile.value.isNotBlank()) callWeb("setSessionId", currentSessionFile.value)
            currentModel.value?.let { model ->
                callWeb("onModelConfirmed", modelKey(model), "pi")
            }
            callWeb("applyBackendTabState", gson.toJson(JsonObject().apply {
                addProperty("provider", "pi")
                currentModel.value?.let { addProperty("model", modelKey(it)) }
                addProperty("permissionMode", "default")
                addProperty("reasoningEffort", currentThinking.value ?: "off")
                add("piThinkingLevels", JsonArray().also { levels -> thinkingLevels.forEach { levels.add(it) } })
            }))
            // Plan 模式状态（pi-plan-mode 扩展），会话恢复/切换后同步给前端 ModeSelect
            callWeb("updatePlanMode", gson.toJson(JsonObject().apply {
                addProperty("active", planModeText.isNotBlank())
                addProperty("text", planModeText)
            }))
        }
    }

    private fun webMessages(): JsonArray = JsonArray().also { result ->
        messages.forEach { message ->
            if (message === streamingAssistant && isStreamingMsg.value) {
                // 流式占位消息：用实时累积的文本渲染，保持消息在会话中的正确顺序
                // （否则末尾追加会导致后续工具消息排在 assistant 之前）
                result.add(assistantMessage(
                    streamingText.value, streamingThinking.value, true, message.getTimestamp()))
            } else if (message.kind == ChatMessage.Kind.TOOL) {
                result.add(toolUseMessage(message))
                if (message.toolStatus != "running" || message.toolResult.isNotBlank()) {
                    result.add(toolResultMessage(message))
                }
            } else {
                result.add(messageJson(message))
            }
        }
        // 流式消息已在 messages 中占位（ensureStreamingAssistant），不再末尾追加
    }

    private fun messageJson(message: ChatMessage): JsonObject = when (message.kind) {
        ChatMessage.Kind.USER -> basicMessage("user", message.text, message.getTimestamp())
        ChatMessage.Kind.ASSISTANT -> assistantMessage(message.text, message.thinking, false, message.getTimestamp())
        ChatMessage.Kind.THINKING -> assistantMessage("", message.thinking.ifBlank { message.text }, false, message.getTimestamp())
        ChatMessage.Kind.SYSTEM -> basicMessage("notification", message.text, message.getTimestamp())
        ChatMessage.Kind.ERROR -> basicMessage("error", message.text, message.getTimestamp())
        ChatMessage.Kind.TOOL -> toolUseMessage(message)
    }

    private fun basicMessage(type: String, text: String, timestamp: Long): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("content", text)
        addProperty("timestamp", Instant.ofEpochMilli(timestamp).toString())
        add("raw", JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
            })
        })
    }

    private fun assistantMessage(text: String, thinking: String, streaming: Boolean, timestamp: Long): JsonObject = JsonObject().apply {
        addProperty("type", "assistant")
        addProperty("content", text)
        addProperty("isStreaming", streaming)
        addProperty("timestamp", Instant.ofEpochMilli(timestamp).toString())
        add("raw", JsonObject().apply {
            add("message", JsonObject().apply {
                add("content", JsonArray().apply {
                    if (thinking.isNotBlank()) add(JsonObject().apply {
                        addProperty("type", "thinking")
                        addProperty("thinking", thinking)
                    })
                    if (text.isNotBlank()) add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                })
            })
        })
    }

    private fun toolUseMessage(message: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("type", "assistant")
        addProperty("content", "")
        addProperty("timestamp", Instant.ofEpochMilli(message.getTimestamp()).toString())
        add("raw", JsonObject().apply {
            add("message", JsonObject().apply {
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_use")
                        addProperty("id", message.toolCallId ?: "tool-${message.timestamp}")
                        addProperty("name", message.toolName ?: "tool")
                        add("input", try {
                            JsonParser.parseString(message.argsSummary).takeIf { it.isJsonObject }?.asJsonObject
                                ?: JsonObject().apply { addProperty("summary", message.argsSummary) }
                        } catch (_: Exception) {
                            JsonObject().apply { addProperty("summary", message.argsSummary) }
                        })
                    })
                })
            })
        })
    }

    private fun toolResultMessage(message: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("type", "user")
        addProperty("content", "[tool_result]")
        addProperty("timestamp", Instant.ofEpochMilli(message.getTimestamp()).toString())
        add("raw", JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", message.toolCallId ?: "tool-${message.timestamp}")
                    addProperty("content", message.toolResult)
                    addProperty("is_error", message.toolStatus == "error")
                })
            })
            // 结构化详情放在 raw.toolUseResult（前端 extractResultMetadata / parseAgentToolMeta 读取）
            message.toolResultDetails?.let { add("toolUseResult", it) }
        })
    }

    private fun callWeb(function: String, vararg args: Any?) {
        if (!webUiReady || browser.isDisposed) return
        val encoded = args.joinToString(",") { arg ->
            when (arg) {
                null -> "null"
                is JsonElement -> gson.toJson(arg)
                is Number, is Boolean -> arg.toString()
                else -> gson.toJson(arg.toString())
            }
        }
        browser.cefBrowser.executeJavaScript(
            "if (typeof window.$function === 'function') window.$function($encoded);",
            "http://pi-chat.local/",
            0
        )
    }

    private fun startClient(target: RpcClient) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                target.start()
            } catch (e: Exception) {
                val msg = "无法启动 pi：\n" + e.message
                onEdt {
                    if (target !== client) return@onEdt
                    connected.value = false
                    statusText.value = "✗ 未连接"
                    messages.add(ChatMessage.error(msg))
                    publishWebState()
                }
            }
        }
    }

    private fun handleStateReady(data: JsonObject, loadHistory: Boolean = true) {
        LOG.info("[PiChatDiag] handleStateReady: " + data)
        connected.value = true
        statusText.value = "● 已连接"
        val sessionFile = data.str("sessionFile")
        currentSessionFile.value = sessionFile
        statusTip.value = (if (sessionFile.isNotEmpty()) "会话文件: $sessionFile\n" else "") + "工作目录: ${client.cwd}"
        val model = if (data.has("model") && data.get("model").isJsonObject) data.getAsJsonObject("model") else null
        val provider = model?.str("provider") ?: ""
        val id = model?.str("id") ?: ""
        val level = data.str("thinkingLevel")
        loadModels(provider, id)
        loadThinkingLevels(if (level.isNotEmpty()) level else "")
        if (loadHistory) loadHistory()
        loadSessionList(sessionFile)
        loadSessionStats()
        publishCommands()
        publishContextPresets()
        publishWebState()
    }

    override fun dispose() {
        client.close()
        browserQuery.dispose()
        browser.dispose()
    }

    // ================= 消息操作（EDT） =================

    private fun addSystem(text: String) {
        messages.add(ChatMessage.system(text))
        publishWebState()
    }

    private fun clearMessages() {
        messages.clear()
        streamingAssistant = null
        isStreamingMsg.value = false
        streamingText.value = ""
        streamingThinking.value = ""
        publishWebState()
    }

    // ================= 动作 =================

    private fun sendMessage(textOverride: String? = null, behavior: String = "steer", images: List<JsonObject> = emptyList()) {
        val text = (textOverride ?: inputText.value.text).trim()
        if (text.isEmpty() || !client.isRunning()) return
        System.out.println("[PiChat] send: " + text.take(50))
        inputText.value = TextFieldValue("")
        messages.add(ChatMessage.user(text))
        scrollRequest.value++
        publishWebState()
        if (streaming) {
            // 对话进行中：steer 打断引导；followUp 排队等待当前对话完成
            if (behavior == "followUp") {
                client.followUp(text, images)
            } else {
                client.promptSteer(text, images)
            }
        } else {
            client.prompt(text, images)
        }
    }

    /** 把前端 attachments（[{fileName, mediaType, data(base64)}]）转成 pi RPC images（[{type,data,mimeType}]）。 */
    private fun parseImageAttachments(attachments: JsonElement?): List<JsonObject> {
        if (attachments == null || !attachments.isJsonArray) return emptyList()
        return attachments.asJsonArray.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val data = o.str("data")
            if (data.isBlank()) return@mapNotNull null
            JsonObject().apply {
                addProperty("type", "image")
                addProperty("data", data)
                addProperty("mimeType", if (o.str("mediaType").isBlank()) "image/png" else o.str("mediaType"))
            }
        }
    }

    private fun abort() {
        client.abort()
        busy.value = false
        addSystem("⏹ 已请求停止")
    }

    private fun newSession() {
        showNewSessionDialog.value = true
    }

    private fun confirmNewSession() {
        showNewSessionDialog.value = false
        // 单会话模式：先停掉旧进程，再启动新会话进程（与终端 pi 一致）
        client.close()
        val next = createClient(client.cwd)
        client = next
        clearMessages()
        currentSessionFile.value = ""
        connected.value = false
        statusText.value = "● 连接中…"
        startClient(next)
    }

    private fun refreshStatus() {
        client.getState().thenAccept { res ->
            onEdt {
                if (res != null && res.success() && res.data() != null) {
                    val d = res.data()
                    val sessionFile = d.str("sessionFile")
                    currentSessionFile.value = sessionFile
                    statusText.value = "● 已连接"
                    statusTip.value = (if (sessionFile.isNotEmpty()) "会话文件: $sessionFile\n" else "") + "工作目录: ${client.cwd}"
                    if (d.has("model") && d.get("model").isJsonObject) {
                        val m = d.getAsJsonObject("model")
                        syncModelSelection(m.str("provider"), m.str("id"))
                    }
                    if (d.has("thinkingLevel")) {
                        syncThinkingSelection(d.str("thinkingLevel"))
                    }
                    publishWebState()
                    loadSessionStats()
                }
            }
        }
    }

    /** Reads Pi's native session counters for the compact status line above the input. */
    private fun loadSessionStats() {
        client.getSessionStats().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null) return@onEdt
                statusText.value = formatPiStatus(res.data())
                // 上下文占用圈数据（TokenIndicator）
                val d = res.data()
                val context = if (d.has("contextUsage") && d.get("contextUsage").isJsonObject) d.getAsJsonObject("contextUsage") else null
                if (context != null) {
                    val used = context.longValue("tokens")
                    val max = context.longValue("contextWindow")
                    val percent = context.numberValue("percent")
                    callWeb("onUsageUpdate", gson.toJson(JsonObject().apply {
                        addProperty("percentage", percent)
                        addProperty("usedTokens", used)
                        addProperty("maxTokens", max)
                    }))
                }
                publishWebState()
            }
        }
    }

    private var statsTail = ""

    /** provider-balance 扩展推送的余额文本（如 "💰 ¥12.34"），空串表示不显示。 */
    private var balanceText = ""
    private val balanceTail: String
        get() = if (balanceText.isNotBlank()) " · $balanceText" else ""

    /** pi-plan-mode 扩展推送的 Plan 状态（plan active/ready/saved/implementing），空串表示未激活。 */
    private var planModeText = ""
    private val planTail: String
        get() = if (planModeText.isNotBlank()) " · $planModeText" else ""

    /** ctx-preset 扩展推送的各模型原始 contextWindow（未被挡位覆盖），用于挡位过滤上限。 */
    private val originalCtxMax = mutableMapOf<String, Long>()

    private fun formatPiStatus(data: JsonObject): String {
        val tokens = data.getAsJsonObject("tokens")
        val context = data.getAsJsonObject("contextUsage")
        val input = tokens?.longValue("input") ?: 0L
        val output = tokens?.longValue("output") ?: 0L
        val cacheRead = tokens?.longValue("cacheRead") ?: 0L
        val used = context?.longValue("tokens") ?: tokens?.longValue("total") ?: 0L
        val max = context?.longValue("contextWindow") ?: 0L
        val percent = context?.numberValue("percent") ?: 0.0
        val cacheBase = input + cacheRead
        val cachePercent = if (cacheBase > 0) cacheRead * 100.0 / cacheBase else 0.0
        val phase = if (busy.value) "正在回复…" else "空闲"
        val contextPart = if (max > 0) "${formatTokenCount(used)}/${formatTokenCount(max)} (${formatPercent(percent)})" else "${formatTokenCount(used)}"
        val tail = " · $contextPart · cache ${formatPercent(cachePercent)} · ↑${formatTokenCount(output)} ↓${formatTokenCount(input)}"
        statsTail = tail
        return "● $phase$tail$planTail$balanceTail"
    }

    private fun JsonObject.longValue(key: String): Long =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrDefault(0L) else 0L

    private fun JsonObject.numberValue(key: String): Double =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asDouble }.getOrDefault(0.0) else 0.0

    private fun formatPercent(value: Double): String =
        if (value >= 10 || value % 1.0 == 0.0) "${value.toInt()}%" else "${String.format(java.util.Locale.ROOT, "%.1f", value)}%"

    private fun formatTokenCount(value: Long): String = when {
        value >= 1_000_000 -> "${String.format(java.util.Locale.ROOT, "%.1f", value / 1_000_000.0)}M"
        value >= 1_000 -> "${String.format(java.util.Locale.ROOT, "%.1f", value / 1_000.0)}K"
        else -> value.toString()
    }

    private fun selectModel(item: ModelItem) {
        showModelPicker.value = false
        val sameModel = currentModel.value?.let { it.provider == item.provider && it.id == item.id } == true
        client.setModel(item.provider, item.id).thenAccept { res ->
            onEdt {
                if (res != null && res.success()) {
                    currentModel.value = item
                    if (!sameModel) addSystem("已切换模型: ${item.name}")
                    loadThinkingLevels("")
                    publishContextPresets()
                } else {
                    addSystem("切换模型失败: " + (res?.error() ?: "无响应"))
                }
            }
        }
    }

    private fun selectThinking(level: String) {
        showThinkingPicker.value = false
        client.setThinkingLevel(level).thenAccept { res ->
            onEdt {
                if (res != null && res.success()) {
                    currentThinking.value = level
                    addSystem("已切换思考强度: $level")
                } else {
                    addSystem("切换思考强度失败: " + (res?.error() ?: "无响应"))
                }
            }
        }
    }

    private fun selectSession(item: SessionItem) {
        showSessionPicker.value = false
        if (item.isCurrent) return

        // 单会话模式：先停掉当前 pi 进程，再启动新进程恢复目标会话
        client.close()
        val next = createClient(client.cwd)
        pendingSessionSwitch[next] = item.path
        client = next
        clearMessages()
        currentSessionFile.value = ""
        connected.value = false
        statusText.value = "● 连接中…"
        startClient(next)
    }

    // ================= 数据加载 =================

    private fun loadModels(currentProvider: String, currentId: String) {
        LOG.info("[PiChatDiag] loadModels(provider=" + currentProvider + ", id=" + currentId + ")")
        client.getAvailableModels().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("models")) {
                    LOG.warn("[PiChatDiag] get_available_models 失败: " + (if (res == null) "null" else res.toString()))
                    return@onEdt
                }
                val arr = res.data().getAsJsonArray("models")
                LOG.info("[PiChatDiag] get_available_models OK, count=" + arr.size())
                val list = mutableListOf<ModelItem>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val m = el.asJsonObject
                    val provider = m.str("provider")
                    val id = m.str("id")
                    val name = if (m.has("name") && !m.get("name").isJsonNull) m.get("name").asString else id
                    val contextWindow = if (m.has("contextWindow") && m.get("contextWindow").isJsonPrimitive)
                        runCatching { m.get("contextWindow").asLong }.getOrDefault(0L) else 0L
                    list.add(ModelItem(provider, id, name, contextWindow))
                }
                models.clear()
                models.addAll(list)
                // 选中当前模型
                val cur = list.firstOrNull { it.provider == currentProvider && it.id == currentId }
                currentModel.value = cur ?: list.firstOrNull()
                publishWebState()
                // Replace the web UI's static fallback catalog with Pi's live
                // get_available_models snapshot once the RPC response arrives.
                publishModels()
            }
        }
    }

    private fun loadThinkingLevels(currentLevel: String) {
        client.getThinkingLevels().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("levels")) return@onEdt
                val arr = res.data().getAsJsonArray("levels")
                val list = mutableListOf<String>()
                for (el in arr) {
                    if (el.isJsonPrimitive) list.add(el.asString)
                }
                val prev = currentThinking.value
                thinkingLevels.clear()
                thinkingLevels.addAll(list)
                currentThinking.value = when {
                    currentLevel.isNotEmpty() && list.contains(currentLevel) -> currentLevel
                    prev != null && list.contains(prev) -> prev
                    else -> list.firstOrNull()
                }
                publishWebState()
            }
        }
    }

    private fun syncModelSelection(provider: String, id: String) {
        val m = models.firstOrNull { it.provider == provider && it.id == id }
        if (m != null) currentModel.value = m
    }

    private fun syncThinkingSelection(level: String) {
        if (level.isNotEmpty() && thinkingLevels.contains(level)) currentThinking.value = level
    }

    private fun loadHistory(force: Boolean = false) {
        // 优先直接读会话文件：会话文件与终端 pi / magic-context 共享，外部写入的最新消息
        // 只能通过读文件拿到（get_messages 返回的是本进程内存快照，会落后）。
        val file = currentSessionFile.value
        if (file.isNotBlank()) {
            val parsed = readSessionFile(file)
            if (parsed != null) {
                // 进程中断时最后一条工具可能没有配对 toolResult：标记中断，避免前端永远转圈
                finalizeInterruptedTools(parsed)
                onEdt {
                    if (!force && messages.isNotEmpty()) return@onEdt
                    if (force) messages.clear()
                    // 重载会话时丢弃未落定的流式状态（可能来自上一会话/中断的流式）
                    resetStreamingState()
                    messages.addAll(parsed)
                    publishWebState()
                }
                return
            }
        }
        client.getMessages().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("messages")) return@onEdt
                if (!force && messages.isNotEmpty()) return@onEdt
                if (force) messages.clear()
                resetStreamingState()
                val arr = res.data().getAsJsonArray("messages")
                LOG.info("[PiChatDiag] loadHistory(force=" + force + ") messages=" + arr.size())
                val parsed = mutableListOf<ChatMessage>()
                val toolMap = HashMap<String, ChatMessage>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    applySessionMessage(parsed, toolMap, el.asJsonObject)
                }
                // 进程中断时最后一条工具可能没有配对 toolResult：标记中断
                finalizeInterruptedTools(parsed)
                messages.addAll(parsed)
                publishWebState()
            }
        }
    }

    /** 重置流式状态（loadHistory 重载/切换会话时调用，避免残留的 streaming 引用污染新会话）。 */
    private fun resetStreamingState() {
        streamingAssistant = null
        isStreamingMsg.value = false
        streamingText.value = ""
        streamingThinking.value = ""
    }

    /**
     * 历史加载收尾：把未配对 toolResult 的 tool 消息（进程中断/会话被强杀，工具没跑完）
     * 标记为中断（error + 说明），否则前端永远显示“运行中”转圈（没有 toolResult 可渲染）。
     */
    private fun finalizeInterruptedTools(parsed: List<ChatMessage>) {
        parsed.forEach { m ->
            if (m.kind == ChatMessage.Kind.TOOL && m.toolStatus == "running") {
                m.toolStatus = "error"
                m.toolResult = "（会话中断，工具未执行完成）"
            }
        }
    }

    /** 直接读会话 jsonl 文件解析历史消息（与终端 pi 共享，能看到外部写入的最新内容）。 */
    private fun readSessionFile(file: String): List<ChatMessage>? {
        return try {
            val parsed = mutableListOf<ChatMessage>()
            val toolMap = HashMap<String, ChatMessage>()
            Files.readAllLines(Path.of(file)).forEach { line ->
                if (line.isBlank()) return@forEach
                val entry = try {
                    gson.fromJson(line, JsonObject::class.java)
                } catch (e: Exception) {
                    return@forEach
                }
                val m = entry?.get("message")
                if (m == null || !m.isJsonObject) return@forEach
                applySessionMessage(parsed, toolMap, m.asJsonObject)
            }
            parsed
        } catch (e: Exception) {
            null
        }
    }

    /** 把一条会话消息（pi AgentMessage 或其 jsonl 形态）转换为前端 ChatMessage。 */
    private fun applySessionMessage(messages: MutableList<ChatMessage>, toolMap: HashMap<String, ChatMessage>, m: JsonObject) {
        if (!m.has("role")) return
        when (m.get("role").asString) {
            "user" -> messages.add(ChatMessage.user(textOf(m.get("content"))))
            "assistant" -> {
                var am = ChatMessage.assistant()
                val content = m.get("content")
                if (content != null && content.isJsonArray) {
                    for (b in content.asJsonArray) {
                        if (!b.isJsonObject) continue
                        val block = b.asJsonObject
                        val t = block.str("type")
                        when (t) {
                            "text" -> {
                                if (block.has("text")) {
                                    val text = block.get("text").asString
                                    // magic-context 压缩重建会把 <thinking>/</thinking> 标签错位：
                                    // open 标签残留进 thinking 内容，close 标签（如 `</thinking>`）残留进 text 块
                                    // （可能是孤立块，也可能与真实文本混合）。统一剥离标签，剥离后为空的块忽略。
                                    // 同时剥离 magic-context 注入的 §N§ / §N°° 消息标记。
                                    val cleaned = stripMagicContextMarks(stripThinkingTags(text))
                                    if (!cleaned.isBlank() && !isStrayThinkingTag(cleaned)) am.appendText(cleaned)
                                }
                            }
                            "thinking" -> {
                                if (block.has("thinking")) {
                                    // 剥离 thinking 内容里残留的 thinking 标签与 magic-context 标记
                                    am.appendThinking(stripMagicContextMarks(stripThinkingTags(block.get("thinking").asString)))
                                }
                            }
                            "toolCall" -> {
                                // 工具调用块：先落定当前 assistant 消息，再作为独立 tool 消息，
                                // 保证 thinking/text 与工具调用的顺序和实时流式一致
                                if (!am.isEmpty) {
                                    messages.add(am)
                                    am = ChatMessage.assistant()
                                }
                                val id = block.str("id").ifEmpty { "tool-" + System.currentTimeMillis() }
                                val name = block.str("name").ifEmpty { "tool" }
                                val args = block.get("arguments")
                                val argsSummary = if (args != null && args.isJsonObject) args.toString() else ""
                                val tm = ChatMessage.tool(id, name, argsSummary)
                                toolMap[id] = tm
                                messages.add(tm)
                            }
                        }
                    }
                } else if (content != null && content.isJsonPrimitive) {
                    am.appendText(content.asString)
                }
                if (!am.isEmpty) messages.add(am)
            }
            "toolResult" -> {
                val name = m.str("toolName") ?: "tool"
                val id = m.str("toolCallId") ?: ""
                // 优先配对到 toolCall 创建的 tool 消息；找不到（压缩可能丢 toolCall）则新建
                val tm = toolMap[id] ?: ChatMessage.tool(id, name, "").also { messages.add(it) }
                tm.toolStatus = if (m.str("isError") == "true") "error" else "done"
                tm.toolResult = textOf(m.get("content"))
            }
            "bashExecution" -> {
                val command = m.str("command") ?: ""
                val tm = ChatMessage.tool("", "bash", command)
                tm.toolStatus = "done"
                tm.toolResult = m.str("output") ?: ""
                messages.add(tm)
            }
        }
    }

    private fun loadSessionList(currentFile: String) {
        if (currentFile.isBlank()) return
        val dir = try {
            Path.of(currentFile).parent
        } catch (e: Exception) {
            null
        } ?: return
        val currentName = try {
            Path.of(currentFile).fileName.toString()
        } catch (e: Exception) {
            ""
        }
        val list = mutableListOf<SessionItem>()
        try {
            Files.list(dir).use { s ->
                s.filter { it.fileName.toString().endsWith(".jsonl") }
                    .sorted(Comparator.comparingLong<Path> { p ->
                        try {
                            Files.getLastModifiedTime(p).toMillis()
                        } catch (e: Exception) {
                            0L
                        }
                    }.reversed())
                    .forEach { p ->
                        val name = p.fileName.toString()
                        val meta = readSessionMeta(p)
                        list.add(
                            SessionItem(
                                p.toString(), name, name == currentName, readSessionTitle(p),
                                parseSessionId(name), meta.first, meta.second, meta.third,
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            // 目录不可读则忽略
        }
        onEdt {
            sessions.clear()
            sessions.addAll(list)
            publishWebState()
            // sessions 更新完成后立即推送历史数据（标题/删除后列表才能刷新）
            publishHistoryData()
        }
    }

    /** 读会话文件元信息（对齐 pi TUI）：首条 user 消息摘要、消息数、最后活动时间戳。
     *  返回 Triple(firstMessage, messageCount, lastTimestampEpochMillis)。 */
    private fun readSessionMeta(file: Path): Triple<String, Int, Long> {
        var firstMessage = ""
        var count = 0
        var lastTs = 0L
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("\"type\":\"message\"")) {
                        count++
                        val ts = extractMessageTimestamp(line)
                        if (ts > 0) lastTs = ts
                        if (firstMessage.isEmpty() && line.contains("\"role\":\"user\"")) {
                            val text = extractFirstUserText(line)
                            if (text.isNotBlank() && text != "[tool_result]") {
                                // 单行化 + 截断（对齐 TUI 的摘要显示）
                                firstMessage = text.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(120)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            // 忽略不可读文件
        }
        if (lastTs == 0L) {
            try { lastTs = Files.getLastModifiedTime(file).toMillis() } catch (e: Exception) {}
        }
        return Triple(firstMessage, count, lastTs)
    }

    /** 从 jsonl 行的 "timestamp":"ISO" 提取 epoch 毫秒。 */
    private fun extractMessageTimestamp(line: String): Long {
        return try {
            val m = Regex("\"timestamp\"\\s*:\\s*\"([^\"]+)\"").find(line) ?: return 0L
            java.time.Instant.parse(m.groupValues[1]).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /** 从 jsonl 行提取首条 user 消息文本（content 为字符串或数组中的首个 text 块）。 */
    private fun extractFirstUserText(line: String): String {
        return try {
            val obj = JsonParser.parseString(line).asJsonObject
            val msg = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
            val content = msg.get("content") ?: return ""
            if (content.isJsonPrimitive) content.asString
            else if (content.isJsonArray) {
                content.asJsonArray.mapNotNull { b ->
                    if (b.isJsonObject && b.asJsonObject.str("type") == "text")
                        b.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString else null
                }.firstOrNull() ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    // ================= 删除会话 =================

    private fun sessionDir(): Path? {
        return try {
            Path.of(currentSessionFile.value).parent
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 会话路径安全校验：只允许操作当前会话目录下的 .jsonl 文件（防路径穿越）。
     * 目录比较忽略大小写 + normalize（pi 报告的文件路径盘符大小写可能与 Files.list 不一致）。
     */
    private fun isAllowedSessionPath(sessionId: String): Boolean {
        val dir = sessionDir() ?: return false
        val target = try {
            Path.of(sessionId)
        } catch (e: Exception) {
            return false
        }
        val targetParent = target.parent?.toAbsolutePath()?.normalize()?.toString() ?: return false
        val dirNorm = dir.toAbsolutePath().normalize().toString()
        return targetParent.equals(dirNorm, ignoreCase = true) &&
            target.fileName.toString().endsWith(".jsonl") &&
            sessions.any { it.path.equals(sessionId, ignoreCase = true) }
    }

    private fun deleteSession(id: String) {
        if (id.isBlank()) return
        val target = try {
            Path.of(id)
        } catch (e: Exception) {
            null
        } ?: run {
            callWeb("addToast", "无法删除：无效的会话路径", "error")
            return
        }
        if (!isAllowedSessionPath(id)) {
            callWeb("addToast", "无法删除：会话不存在", "error")
            return
        }
        val deleted = try {
            Files.deleteIfExists(target)
        } catch (e: Exception) {
            false
        }
        // 成功时前端已乐观弹提示，这里只在失败时提示，避免重复
        if (!deleted) callWeb("addToast", "删除失败：文件可能被占用", "error")
        loadSessionList(currentSessionFile.value)
    }

    private fun deleteSessions(content: String) {
        val ids = try {
            val arr = JsonParser.parseString(content).asJsonArray
            arr.mapNotNull { el -> if (el.isJsonPrimitive) el.asString else null }
        } catch (e: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) return
        var ok = 0
        var fail = 0
        for (id in ids) {
            val target = try {
                Path.of(id)
            } catch (e: Exception) {
                null
            }
            if (target == null || !isAllowedSessionPath(id)) {
                fail++
                continue
            }
            try {
                if (Files.deleteIfExists(target)) ok++ else fail++
            } catch (e: Exception) {
                fail++
            }
        }
        // 成功时前端已乐观弹提示，这里只在失败时提示，避免重复
        if (ok == 0 && fail > 0) callWeb("addToast", "删除失败：$fail 个会话未能删除", "error")
        else if (fail > 0) callWeb("addToast", "已删除 $ok 个，$fail 个失败", "warning")
        loadSessionList(currentSessionFile.value)
    }

    // ================= 修改会话标题 =================

    /**
     * 从会话文件尾部反向读取最新的 session_info 记录（pi 的标题存储方式），
     * 与终端 pi 的 renameSession 行为一致。
     */
    private fun readSessionTitle(path: Path): String? {
        return try {
            java.io.RandomAccessFile(path.toFile(), "r").use { raf ->
                val size = raf.length()
                if (size <= 0) return@use null
                val tailLen = minOf(size, 64L * 1024)
                raf.seek(size - tailLen)
                val bytes = ByteArray(tailLen.toInt())
                raf.readFully(bytes)
                val tail = String(bytes, StandardCharsets.UTF_8)
                var idx = tail.lastIndexOf("\"session_info\"")
                while (idx >= 0) {
                    val lineStart = tail.lastIndexOf('\n', idx)
                    val lineEnd = tail.indexOf('\n', idx)
                    val line = tail.substring(lineStart + 1, if (lineEnd < 0) tail.length else lineEnd)
                    val nameMatch = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)
                    if (nameMatch != null) {
                        return nameMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                    idx = tail.lastIndexOf("\"session_info\"", idx - 1)
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateSessionTitle(content: String) {
        val sessionId = try {
            JsonParser.parseString(content).asJsonObject.str("sessionId")
        } catch (e: Exception) {
            ""
        }
        val title = try {
            JsonParser.parseString(content).asJsonObject.str("customTitle")
        } catch (e: Exception) {
            ""
        }
        if (sessionId.isBlank()) return
        val target = try {
            Path.of(sessionId)
        } catch (e: Exception) {
            null
        } ?: run {
            callWeb("addToast", "无法修改标题：无效的会话路径", "error")
            return
        }
        if (!isAllowedSessionPath(sessionId)) {
            callWeb("addToast", "无法修改标题：会话不存在", "error")
            return
        }
        val clean = title.replace(Regex("[\r\n]+"), " ").trim()
        if (clean.isEmpty()) {
            callWeb("addToast", "标题不能为空", "error")
            return
        }
        try {
            // 读取最后一条记录的 id 作为 parentId，保持 pi 的会话树结构
            var leafId = ""
            Files.readAllLines(target, StandardCharsets.UTF_8).forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val obj = JsonParser.parseString(line).asJsonObject
                    if (obj.has("id") && !obj.get("id").isJsonNull) leafId = obj.get("id").asString
                } catch (e: Exception) {
                    // 忽略损坏行
                }
            }
            val entry = JsonObject().apply {
                addProperty("type", "session_info")
                addProperty("id", java.util.UUID.randomUUID().toString())
                if (leafId.isNotEmpty()) addProperty("parentId", leafId)
                addProperty("timestamp", java.time.Instant.now().toString())
                addProperty("name", clean)
            }
            Files.writeString(
                target, gson.toJson(entry) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
            )
            callWeb("addToast", "标题已更新", "success")
            loadSessionList(currentSessionFile.value)
        } catch (e: Exception) {
            callWeb("addToast", "更新标题失败：${e.message}", "error")
        }
    }

    // ================= 上下文挡位（ctx-preset 扩展） =================

    private fun ctxPresetFile(): Path =
        Path.of(System.getProperty("user.home"), ".pi", "agent", "ctx-preset.json")

    /** 推送当前模型的上下文挡位信息给前端选择器。 */
    private fun publishContextPresets() {
        if (!webUiReady) return
        val model = currentModel.value
        var persistedK = -1
        try {
            val file = ctxPresetFile()
            if (Files.exists(file)) {
                val cfg = JsonParser.parseString(Files.readString(file)).asJsonObject
                val presets = if (cfg.has("presets") && cfg.get("presets").isJsonObject) cfg.getAsJsonObject("presets") else null
                model?.let { m ->
                    presets?.get("${m.provider}/${m.id}")?.takeIf { it.isJsonPrimitive }?.let { persistedK = (it.asLong / 1000).toInt() }
                }
            }
        } catch (e: Exception) {
            // 配置不可读则忽略
        }
        // 挡位过滤上限：优先用 ctx-preset 扩展推送的模型原始 contextWindow（未被挡位覆盖，
        // 避免已持久化挡位导致 get_available_models 返回覆盖值而低估上限），回退当前值。
        val originalMaxK = model?.let { m -> originalCtxMax["${m.provider}/${m.id}"]?.div(1000) } ?: 0
        val modelMaxK = if (originalMaxK > 0) originalMaxK else (model?.contextWindow ?: 0L) / 1000
        val levels = readCtxPresetLevels()
        // 挡位跟随模型上限：只保留不超过模型原始 contextWindow 的挡位（与 TUI /ctx 选择器一致）。
        // 模型原始上限未知（contextWindow<=0）时不过滤，展示全部挡位。
        val effectiveLevels = if (modelMaxK > 0) levels.filter { it <= modelMaxK } else levels
        // 无可用挡位（如模型仅 180k < 最小挡 256k）→ currentK=0 + presets 空，前端隐藏选择器（没必要调整）。
        // currentK 优先显示当前实际生效值（pi 报告，可能已被挡位覆盖），回退持久化挡位/原始上限。
        val currentModelK = (model?.contextWindow ?: 0L) / 1000
        val currentK = if (effectiveLevels.isEmpty()) 0
            else if (currentModelK > 0) currentModelK
            else if (persistedK > 0) persistedK
            else modelMaxK
        onEdt {
            callWeb("updateContextPresets", gson.toJson(JsonObject().apply {
                addProperty("currentK", currentK)
                addProperty("persistedK", persistedK)
                add("presets", JsonArray().also { arr -> effectiveLevels.forEach { arr.add(it) } })
                model?.let { addProperty("modelKey", "${it.provider}/${it.id}") }
            }))
        }
    }

    /** 手动压缩会话上下文：发送 pi 内置 /compact 命令（等价 TUI 的 /compact）。 */
    private fun handleCompactSession() {
        if (!client.isRunning()) {
            callWeb("addToast", "pi 未连接，无法压缩", "error")
            return
        }
        client.prompt("/compact")
        callWeb("addToast", "正在压缩上下文…", "info")
    }

    /** 从 ctx-preset 扩展源码读取挡位表（PRESETS 的键，单位 K），动态跟随用户配置（如新增 512）。
     *  路径 ~/.pi/agent/extensions/ctx-preset/index.ts，匹配 `"200": 200_000` 形式的纯数字键。 */
    private fun readCtxPresetLevels(): List<Int> {
        val fallback = listOf(200, 400, 1000)
        return try {
            val home = System.getProperty("user.home")
            val file = Path.of(home, ".pi", "agent", "extensions", "ctx-preset", "index.ts")
            if (!Files.exists(file)) return fallback
            val src = Files.readString(file)
            val pattern = Regex("\"(\\d+)\"\\s*:\\s*[\\d_]+")
            val levels: List<Int> = pattern.findAll(src)
                .map { it.groupValues[1].toIntOrNull() }
                .filterNotNull()
                .distinct()
                .sorted()
                .toList()
            if (levels.isEmpty()) fallback else levels
        } catch (e: Exception) {
            fallback
        }
    }

    /** 设置挡位：与 TUI `/ctx <level> --p` 一致——运行时应用 + 持久化，无需重启进程。
     *  ctx-preset 扩展通过 pi.registerProvider() 重新注册当前模型（覆盖 contextWindow）
     *  立即生效，并把挡位写入 ~/.pi/agent/ctx-preset.json（下次 session_start 自动应用）。 */
    private fun handleSetContextPreset(content: String) {
        val level = content.trim().toIntOrNull()
        val levels = readCtxPresetLevels()
        if (level == null || level !in levels) {
            callWeb("addToast", "可用挡位: " + levels.joinToString(" / "), "error")
            return
        }
        val model = currentModel.value ?: run {
            callWeb("addToast", "当前没有活动模型", "warning")
            return
        }
        if (!client.isRunning()) {
            callWeb("addToast", "pi 未连接，无法设置挡位", "error")
            return
        }
        // 发送扩展命令 /ctx 400 --p：扩展立即 applyContextWindow（内存重注册）并持久化
        client.prompt("/ctx $level --p")
        callWeb("addToast", "已设置 ${model.provider}/${model.id} = ${level}k", "success")
        // 扩展异步应用 + 写配置，稍后刷新前端挡位显示与状态栏
        javax.swing.Timer(1500) {
            publishContextPresets()
            loadSessionStats()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun textOf(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        val sb = StringBuilder()
        for (el in content.asJsonArray) {
            if (el.isJsonObject) {
                val block = el.asJsonObject
                if (block.has("text") && !block.get("text").isJsonNull) sb.append(block.get("text").asString)
            }
        }
        return sb.toString()
    }

    /**
     * magic-context 压缩重建会把 thinking 标签错位：close 标签（`</thinking>`/` response` 等）
     * 被孤立成 text 块。识别这种纯标签残留文本，避免污染消息正文。
     */
    private fun isStrayThinkingTag(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() ||
            trimmed == "</thinking>" || trimmed == "<thinking>" ||
            trimmed == "</reasoning>" || trimmed == "<reasoning>" ||
            trimmed == "</thought>" || trimmed == "<thought>" ||
            trimmed == " response" || trimmed == " thinking" ||
            trimmed == "response"
    }

    /** 剥离 thinking 内容里残留的 thinking 标签（open/close，含 </think> 等变体）。 */
    private fun stripThinkingTags(text: String): String {
        var out = text
        for (tag in listOf(
            "<thinking>", "</thinking>", "<think>", "</think>",
            "<reasoning>", "</reasoning>",
            "<thought>", "</thought>",
            "<analysis>", "</analysis>"
        )) {
            out = out.replace(tag, "")
        }
        return out
    }

    /**
     * 复刻 magic-context 的 stripPersistedAssistantText（packages/plugin/src/hooks/magic-context/tag-content-primitives.ts）
     * 标记清除规则，按序执行：
     * 1) 开头规范 §N§ 前缀（一个或多个 + 尾随空格）
     * 2) 全局完整 §N§ 对
     * 3) malformed hybrid：§N">§ / §N">§N§ / §N">
     * 4) dangling：§N + 单个 improvised closer（$、ҩ、° 等非 word/空格/§/句点字符）
     * 5) 补充：magic-context 的 dangling 只吃掉一个 °，这里把 §N°° 的残余 ° 和孤立 °° 清掉
     * 6) stray §
     * 最后 trim。只用于 assistant 消息文本；user/tool 消息保留。
     * § = U+00A7，° = U+00B0（用 \u 转义避免源码字面量字节歧义）。
     */
    private fun stripMagicContextMarks(text: String): String {
        var out = text
        // 1) 开头规范前缀：一个或多个 §N§ + 尾随空格
        out = out.replace(Regex("^(\\u00A7\\d+\\u00A7\\s*)+"), "")
        // 2) 全局完整对 §N§
        out = out.replace(Regex("\\u00A7\\d+\\u00A7"), "")
        // 3) malformed hybrid：§N">§ / §N">§N§ / §N">
        out = out.replace(Regex("\\u00A7\\d+\">(?:\\u00A7(?:\\d+\\u00A7)?)?"), "")
        // 4) dangling：§N + 单个 improvised closer（非 word/空格/§/句点；不碰 §5.1 小数引用）
        out = out.replace(Regex("\\u00A7\\d+(?!\\.\\d)[^\\s\\u00A7\\w.]?"), "")
        // 5) 补充：清残余的 °°（magic-context 只吃一个 °，模型 improvised 的 °° 闭合符会残留）
        out = out.replace(Regex("\\u00B0{2,}"), "")
        out = out.replace(Regex("^\\s*\\u00B0+"), "")
        // 6) stray §
        out = out.replace(Regex("\\u00A7"), "")
        return out.trim()
    }

    private fun JsonObject.str(key: String): String {
        return if (has(key) && !get(key).isJsonNull) get(key).asString else ""
    }

    private fun onEdt(fn: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { fn() }
    }

    // ================= PiListener（RPC 线程 → EDT） =================

    override fun onMessageUpdate(update: JsonObject) {
        if (!update.has("assistantMessageEvent") || !update.get("assistantMessageEvent").isJsonObject) return
        val ame = update.getAsJsonObject("assistantMessageEvent")
        val type = ame.str("type")
        when (type) {
            "text_start", "thinking_start" -> ensureStreamingAssistant()
            "text_delta" -> {
                ensureStreamingAssistant()
                if (ame.has("delta")) {
                    onEdt {
                        streamingText.value += ame.get("delta").asString
                        publishWebState()
                    }
                }
            }
            "thinking_delta" -> {
                ensureStreamingAssistant()
                if (ame.has("delta")) {
                    onEdt {
                        streamingThinking.value += ame.get("delta").asString
                        publishWebState()
                    }
                }
            }
        }
    }

    private fun ensureStreamingAssistant() {
        if (streamingAssistant != null) return
        val sa = ChatMessage.assistant()
        streamingAssistant = sa
        onEdt {
            // 占位：流式消息按事件顺序插入 messages，保证后续工具消息排在其后
            messages.add(sa)
            isStreamingMsg.value = true
            streamingText.value = ""
            streamingThinking.value = ""
            publishWebState()
        }
    }

    override fun onToolStart(toolCallId: String, toolName: String, args: JsonObject?) {
        val argsSummary = args?.toString() ?: ""
        onEdt {
            messages.add(ChatMessage.tool(toolCallId, toolName, argsSummary))
            publishWebState()
        }
    }

    override fun onToolUpdate(toolCallId: String, toolName: String, partialResult: JsonObject?) {
        onEdt {
            val m = findToolMessage(toolCallId)
            if (m == null || partialResult == null) return@onEdt
            if (partialResult.has("content")) {
                m.toolResult = textFromContent(partialResult.get("content"))
            }
            // 运行中的 subagent 也会通过 partialResult.details 推送增量进度，
            // 同步更新以便展开时能实时看到部分子代理结果
            if (partialResult.has("details")) {
                m.toolResultDetails = partialResult.get("details")
            }
            publishWebState()
        }
    }

    override fun onToolEnd(toolCallId: String, toolName: String, isError: Boolean, result: JsonObject?) {
        onEdt {
            val m = findToolMessage(toolCallId) ?: ChatMessage.tool(toolCallId, toolName, "").also { messages.add(it) }
            m.toolStatus = if (isError) "error" else "done"
            if (result != null && result.has("content")) {
                m.toolResult = textFromContent(result.get("content"))
            }
            // 保留结构化详情（如 subagent 工具的 details.results[].messages），供 load_subagent_session 读取
            if (result != null && result.has("details")) {
                m.toolResultDetails = result.get("details")
            }
            publishWebState()
        }
    }

    private fun findToolMessage(toolCallId: String): ChatMessage? {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.kind == ChatMessage.Kind.TOOL && toolCallId != null && toolCallId == m.toolCallId) return m
        }
        return null
    }

    override fun onAgentStart() {
        streaming = true
        onEdt {
            busy.value = true
            // 保留统计尾部，不被纯 working 覆盖；随后异步刷新最新统计
            statusText.value = "● 正在回复…$statsTail$planTail$balanceTail"
            publishWebState()
            loadSessionStats()
        }
    }

    override fun onMessageEnd(message: JsonObject) {
        // message_end：一条消息在 pi 进程内已完成（magic-context 已在持久化前清除标记）。
        // 只处理 assistant 消息——user/toolResult 的 message_end 不应落定流式中的 assistant。
        val msg = if (message.has("message") && message.get("message").isJsonObject)
            message.getAsJsonObject("message") else null
        if (msg == null || msg.str("role") != "assistant") return
        // 在此落定，保证后续工具消息（tool_execution_start）按语义顺序排在其后
        val arr = JsonArray().also { it.add(msg) }
        val full = extractAssistantContent(arr)
        onEdt { finalizeStreaming(full) }
    }

    override fun onAgentEnd(agentMessages: JsonArray?, willRetry: Boolean) {
        onEdt {
            finalizeStreaming(agentMessages?.let { extractAssistantContent(it) })
            publishWebState()
        }
    }

    /**
     * 落定当前流式 assistant 消息到 messages（保持语义顺序），并清除 magic-context 标记。
     * full 非空时优先用完整消息（已清除），否则用流式累积的 streamingText/streamingThinking 兜底。
     */
    private fun finalizeStreaming(full: Pair<String, String>?) {
        val sa = streamingAssistant ?: return
        if (full != null) {
            // 落定时清除 magic-context 标记（对齐 TUI 的 message_end 行为：
            // 流式 delta 可能携带模型 mimic 的 §N§ 标记，落定后清除）
            sa.setText(stripMagicContextMarks(full.first))
            sa.setThinking(stripMagicContextMarks(full.second))
        } else {
            val t = streamingText.value
            val th = streamingThinking.value
            if (t.isNotEmpty()) sa.appendText(stripMagicContextMarks(t))
            if (th.isNotEmpty()) sa.appendThinking(stripMagicContextMarks(th))
        }
        if (sa.isEmpty) {
            messages.remove(sa)
        } else if (!messages.contains(sa)) {
            // 正常情况下 ensureStreamingAssistant 已占位；此处兜底
            messages.add(sa)
        }
        streamingAssistant = null
        isStreamingMsg.value = false
        streamingText.value = ""
        streamingThinking.value = ""
        // 落定后立即刷新 UI（否则要等到下一个事件才推送，前端会短暂停留在流式状态）
        publishWebState()
    }

    /** 从 agent_end 的完整消息列表中提取最后一条有内容的 assistant 消息。 */
    private fun extractAssistantContent(agentMessages: JsonArray): Pair<String, String>? {
        for (i in agentMessages.size() - 1 downTo 0) {
            val el = agentMessages.get(i)
            if (!el.isJsonObject) continue
            val m = el.asJsonObject
            if (!m.has("role") || m.get("role").asString != "assistant") continue
            val content = m.get("content")
            var text = ""
            var thinking = ""
            if (content != null && content.isJsonArray) {
                for (b in content.asJsonArray) {
                    if (!b.isJsonObject) continue
                    val block = b.asJsonObject
                    val t = block.str("type")
                    if (t == "text" && block.has("text")) text += block.get("text").asString
                    else if (t == "thinking" && block.has("thinking")) thinking += block.get("thinking").asString
                }
            } else if (content != null && content.isJsonPrimitive) {
                text = content.asString
            }
            if (text.isNotEmpty() || thinking.isNotEmpty()) return text to thinking
        }
        return null
    }

    override fun onAgentSettled() {
        streaming = false
        onEdt {
            busy.value = false
            refreshStatus()
            publishWebState()
            // 通知前端 agent 回合真正完成（followUp 排队消息 drain 的可靠信号）
            callWeb("onAgentCompleted")
        }
    }

    override fun onQueueUpdate(queue: JsonObject) {
        val steering = if (queue.has("steering") && queue.get("steering").isJsonArray) queue.getAsJsonArray("steering").size() else 0
        val followUp = if (queue.has("followUp") && queue.get("followUp").isJsonArray) queue.getAsJsonArray("followUp").size() else 0
        val total = steering + followUp
        onEdt {
            queueCount.value = total
            statusText.value = when {
                busy.value -> "● 正在回复…$statsTail$planTail$balanceTail"
                total > 0 -> "● 队列:$total$statsTail$planTail$balanceTail"
                else -> "● 已连接$statsTail$planTail$balanceTail"
            }
            publishWebState()
        }
    }

    override fun onExtensionUi(req: ExtensionUiRequest) {
        onEdt { handleExtensionUi(req) }
    }

    private fun handleExtensionUi(req: ExtensionUiRequest) {
        when (req.method()) {
            "notify" -> {
                val text = req.message()
                val notifyType = req.raw().str("notifyType")
                // 命令输出等通知内容渲染到聊天消息区（贴近 tui 的命令回显），
                // 而不是只弹右下角系统通知。
                if (text.isNotBlank()) {
                    messages.add(ChatMessage.system(text))
                }
                // 错误/警告级别仍弹系统通知，确保用户注意到异常。
                val type = when (notifyType) {
                    "warning" -> NotificationType.WARNING
                    "error" -> NotificationType.ERROR
                    else -> NotificationType.INFORMATION
                }
                if (type != NotificationType.INFORMATION && text.isNotBlank()) {
                    var group = NotificationGroupManager.getInstance().getNotificationGroup("Pi Chat")
                    if (group == null) {
                        group = NotificationGroup("Pi Chat", NotificationDisplayType.BALLOON, true)
                    }
                    group.createNotification(text.take(500), type).notify(project)
                }
            }
            "select" -> forwardExtensionDialog(req)
            "confirm" -> forwardExtensionDialog(req)
            "input" -> forwardExtensionDialog(req)
            "editor" -> forwardExtensionDialog(req)
            "set_editor_text" -> {
                if (req.raw().has("text") && !req.raw().get("text").isJsonNull) {
                    inputText.value = TextFieldValue(req.raw().get("text").asString)
                }
            }
            "setStatus" -> handleSetStatus(req)
            else -> {
                // setStatus / setWidget / setTitle 及未知请求：第一版忽略
            }
        }
        publishWebState()
    }

    /** 处理扩展 setStatus：provider-balance（套餐余额）与 plan-mode（Plan 状态）追加到状态栏尾部。 */
    private fun handleSetStatus(req: ExtensionUiRequest) {
        val key = req.raw().str("statusKey")
        val text = req.raw().str("statusText")
        when (key) {
            "provider-balance" -> balanceText = text
            "plan-mode" -> {
                planModeText = text
                // 推给前端 ModeSelect（当前 Plan 模式 + 子状态），会话恢复时也能同步
                callWeb("updatePlanMode", gson.toJson(JsonObject().apply {
                    addProperty("active", text.isNotBlank())
                    addProperty("text", text)
                }))
            }
            "optimize-result" -> {
                // editor-prompt-optimize 扩展的优化结果：回传前端 usePromptEnhancer 对话框
                if (text.isBlank()) {
                    callWeb("updateEnhancedPrompt", "{\"success\":false,\"error\":\"优化失败，未得到结果\",\"done\":true}")
                } else {
                    callWeb("updateEnhancedPrompt", gson.toJson(JsonObject().apply {
                        addProperty("success", true)
                        addProperty("enhancedPrompt", text)
                        addProperty("done", true)
                    }))
                }
            }
            "ctx-original-max" -> {
                // ctx-preset 扩展推送的模型原始 contextWindow（未被挡位覆盖），
                // 用于挡位过滤上限（不追加状态栏，直接返回）。
                try {
                    val obj = JsonParser.parseString(text).asJsonObject
                    val key = obj.get("key").asString
                    val max = obj.get("max").asLong
                    if (key.isNotBlank() && max > 0) originalCtxMax[key] = max
                } catch (e: Exception) {
                    // 解析失败忽略
                }
                return
            }
            else -> return
        }
        statusText.value = when {
            busy.value -> "● 正在回复…$statsTail$planTail$balanceTail"
            queueCount.value > 0 -> "● 队列:${queueCount.value}$statsTail$planTail$balanceTail"
            else -> "● 已连接$statsTail$planTail$balanceTail"
        }
        publishWebState()
    }

    /** 切换 plan 模式：通过 pi-plan-mode 扩展命令 /plan start | /plan exit（RPC 支持扩展命令）。 */
    private fun handleSetPlanMode(content: String) {
        val mode = content.trim().removeSurrounding("\"")
        when (mode) {
            "plan" -> {
                client.prompt("/plan start")
                callWeb("addToast", "已进入 Plan 模式（只读探索 + 计划）", "info")
            }
            "normal" -> {
                client.prompt("/plan exit")
                callWeb("addToast", "已退出 Plan 模式", "info")
            }
            else -> callWeb("addToast", "未知模式: $mode", "error")
        }
    }

    /**
     * 优化提示词：把输入框文本发给 pi 的 editor-prompt-optimize 扩展（/optimize_text 命令）
     * 优化。结果是异步的：扩展完成后经 setStatus("optimize-result") 推送回来（见 handleSetStatus），
     * 再转发给前端 usePromptEnhancer 对话框（updateEnhancedPrompt）。
     */
    private fun handleEnhancePrompt(content: String) {
        val payload = try {
            JsonParser.parseString(content).asJsonObject
        } catch (_: Exception) {
            null
        }
        val prompt = payload?.str("prompt") ?: content
        if (prompt.isBlank()) {
            callWeb("updateEnhancedPrompt", "{\"success\":false,\"error\":\"提示词为空\",\"done\":true}")
            return
        }
        if (!client.isRunning()) {
            callWeb("updateEnhancedPrompt", "{\"success\":false,\"error\":\"pi 未连接\",\"done\":true}")
            return
        }
        client.prompt("/optimize_text $prompt")
    }

    /** 提示词优化配置：~/.pi/agent/editor-prompt-optimize.json（{model, thinking}，与 TUI 扩展共享）。 */
    private val optimizeConfigFile: Path
        get() = Path.of(System.getProperty("user.home"), ".pi", "agent", "editor-prompt-optimize.json")

    /** 推给前端设置页：当前配置 + 可选模型列表 + 可用推理强度。 */
    private fun publishOptimizeSettings() {
        var model = ""
        var thinking = ""
        try {
            if (Files.exists(optimizeConfigFile)) {
                val o = gson.fromJson(Files.readString(optimizeConfigFile), JsonObject::class.java) ?: JsonObject()
                model = o.str("model")
                thinking = o.str("thinking")
            }
        } catch (_: Exception) {
        }
        callWeb("updateOptimizeSettings", gson.toJson(JsonObject().apply {
            addProperty("model", model)
            addProperty("thinking", thinking)
            add("models", JsonArray().also { arr ->
                models.forEach { m ->
                    arr.add(JsonObject().apply {
                        // 扩展 resolveModel 期望 provider/modelId（斜杠）
                        addProperty("key", "${m.provider}/${m.id}")
                        addProperty("label", "${m.provider} / ${m.name}")
                    })
                }
            })
            add("thinkingLevels", JsonArray().also { arr -> thinkingLevels.forEach { arr.add(it) } })
        }))
    }

    /** 保存优化设置：写 editor-prompt-optimize.json（model + thinking），下次优化立即生效。 */
    private fun handleSetOptimizeSettings(content: String) {
        try {
            val o = JsonParser.parseString(content).asJsonObject
            val cfg = JsonObject()
            val model = o.str("model")
            val thinking = o.str("thinking")
            if (model.isNotBlank()) cfg.addProperty("model", model)
            if (thinking.isNotBlank()) cfg.addProperty("thinking", thinking)
            Files.createDirectories(optimizeConfigFile.parent)
            Files.writeString(optimizeConfigFile, gson.toJson(cfg))
            callWeb("addToast", "提示词优化设置已保存", "success")
        } catch (e: Exception) {
            callWeb("addToast", "保存优化设置失败: ${e.message}", "error")
        }
    }

    /**
     * 把 pi 的 extension_ui 对话框请求（select/confirm/input/editor）转发到 webview
     * 的 AskUserQuestionDialog（JCEF 渲染层），响应经 ask_user_question_response 桥接回来。
     */
    private fun forwardExtensionDialog(req: ExtensionUiRequest) {
        val questions = JsonArray()
        when (req.method()) {
            "select" -> {
                val opts = JsonArray()
                req.options().forEach { opts.add(it) }
                questions.add(JsonObject().apply {
                    addProperty("question", req.title().ifEmpty { "请选择" })
                    addProperty("header", "")
                    add("options", opts)
                    addProperty("multiSelect", false)
                })
            }
            "confirm" -> {
                val text = req.title().ifEmpty { "确认" }
                questions.add(JsonObject().apply {
                    addProperty("question", if (req.message().isNotEmpty()) "$text\n\n${req.message()}" else text)
                    addProperty("header", "")
                    add("options", JsonArray().also { it.add("是"); it.add("否") })
                    addProperty("multiSelect", false)
                })
            }
            "input", "editor" -> {
                questions.add(JsonObject().apply {
                    addProperty("question", req.title().ifEmpty { "请输入" })
                    addProperty("header", "")
                    // __OTHER__ 在前端渲染为“其他 + 自定义输入框”
                    add("options", JsonArray().also { it.add("__OTHER__") })
                    addProperty("multiSelect", false)
                })
            }
            else -> return
        }
        askUserByRequestId[req.id()] = req
        callWeb("showAskUserQuestionDialog", gson.toJson(JsonObject().apply {
            addProperty("requestId", req.id())
            addProperty("toolName", "pi")
            add("questions", questions)
            addProperty("provider", "pi")
        }))
    }

    /** 解析 webview AskUserQuestionDialog 的响应并回填 pi 的 extension_ui_response。 */
    private fun handleAskUserResponse(payload: String) {
        val body = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (_: Exception) {
            return
        }
        val requestId = body.str("requestId")
        val req = askUserByRequestId.remove(requestId) ?: return
        val answers = if (body.has("answers") && body.get("answers").isJsonObject) {
            body.getAsJsonObject("answers")
        } else {
            JsonObject()
        }
        val value = answers.entrySet().firstOrNull()
            ?.value?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        when (req.method()) {
            "confirm" -> completeExtension(req, confirmed = value == "是" || value == "Yes")
            "select", "input", "editor" -> {
                if (value.isEmpty()) cancelExtension(req) else completeExtension(req, value = value)
            }
        }
    }

    override fun onProcessExit(exitCode: Int, stderrTail: String?) {
        onEdt {
            connected.value = false
            statusText.value = "✗ 已断开"
            busy.value = false
            if (!client.isRunning()) {
                val tail = stderrTail?.take(800)
                messages.add(
                    ChatMessage.error(
                        "pi 进程已退出（code=$exitCode）" + (if (tail.isNullOrBlank()) "" else "\n$tail")
                    )
                )
            }
            publishWebState()
        }
    }

    override fun onError(message: String) {
        onEdt {
            messages.add(ChatMessage.error(message))
            publishWebState()
        }
    }

    private fun textFromContent(content: JsonElement): String {
        if (content.isJsonPrimitive) return content.asString
        val sb = StringBuilder()
        for (el in content.asJsonArray) {
            if (el.isJsonObject) {
                val block = el.asJsonObject
                if (block.has("text") && !block.get("text").isJsonNull) sb.append(block.get("text").asString)
            }
        }
        return sb.toString()
    }

    private fun cancelExtension(req: ExtensionUiRequest) {
        extensionDialog.value = null
        client.respondExtensionUi(req.id()) { o -> o.addProperty("cancelled", true) }
        publishWebState()
    }

    private fun completeExtension(req: ExtensionUiRequest, value: String? = null, confirmed: Boolean? = null) {
        extensionDialog.value = null
        client.respondExtensionUi(req.id()) { o ->
            if (value != null) o.addProperty("value", value)
            if (confirmed != null) o.addProperty("confirmed", confirmed)
        }
        publishWebState()
    }

    // ================= Compose UI =================

    @Composable
    fun Content() {
        val dark = isDarkTheme()
        Column(Modifier.fillMaxSize().background(if (dark) Color(0x24272F) else Color(0xF7F8FA))) {
            TopBar(dark)
            MessagesArea(Modifier.weight(1f), dark)
            InputArea(dark)
        }
        PickerDialogs(dark)
        NewSessionDialog(dark)
        ExtensionDialogOverlay(dark)
    }

    @Composable
    private fun isDarkTheme(): Boolean {
        val manager = com.intellij.util.ui.UIUtil.isUnderDarcula()
        return remember(manager) { manager }
    }

    // ---------------- 顶部栏 ----------------

    @Composable
    private fun TopBar(dark: Boolean) {
        val fg = if (dark) Color(0xE6EAF0) else Color(0x24292E)
        val sub = if (dark) Color(0x9AA3B2) else Color(0x6E7781)
        val border = if (dark) Color(0x3C414B) else Color(0xE0E3E8)
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (dark) Color(0x292C34) else Color(0xFFFFFF))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (dark) Color(0x315C9EEA) else Color(0xDCEBFF)), contentAlignment = Alignment.Center) {
                    Text("π", color = if (dark) Color(0x75C7FF) else Color(0x236DCE), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pi Chat", color = fg, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            !connected.value -> Color(0xEF6A6A)
                            busy.value -> Color(0xF0A64B)
                            else -> Color(0x55D5AD)
                        }
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                        Spacer(Modifier.width(5.dp))
                        Text(statusText.value.removePrefix("● "), color = sub, style = TextStyle(fontSize = 11.sp), maxLines = 1)
                    }
                }
                HeaderAction("⋯", dark) { showSessionPicker.value = true }
                Spacer(Modifier.width(4.dp))
                HeaderAction("＋", dark) { newSession() }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                PickerButton("模型", currentModel.value?.name ?: "选择模型", dark, Modifier.weight(1f)) { showModelPicker.value = true }
                PickerButton("推理", currentThinking.value ?: "off", dark, Modifier.weight(0.72f)) { showThinkingPicker.value = true }
                PickerButton("历史", "${sessions.size}", dark, Modifier.widthIn(min = 58.dp, max = 74.dp)) { showSessionPicker.value = true }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上下文", color = sub, style = TextStyle(fontSize = 10.5.sp))
                Spacer(Modifier.width(6.dp))
                Text("${messages.count { it.kind != ChatMessage.Kind.SYSTEM }} 条消息", color = fg, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium))
                Spacer(Modifier.width(8.dp))
                Text("·", color = sub)
                Spacer(Modifier.width(8.dp))
                Text(currentSessionFile.value.substringAfterLast('\\').substringAfterLast('/').ifEmpty { "当前会话" }, color = sub, style = TextStyle(fontSize = 10.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Divider(Orientation.Horizontal, color = border, thickness = 1.dp)
    }

    @Composable
    private fun HeaderAction(label: String, dark: Boolean, onClick: () -> Unit) {
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, color = if (dark) Color(0xAAB4C2) else Color(0x6E7781), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium))
        }
    }

    @Composable
    private fun PickerButton(label: String, value: String, dark: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val fg = if (dark) Color(0xE2E7EF) else Color(0x3C4043)
        val border = if (dark) Color(0x454B57) else Color(0xD4D8DD)
        val bg = if (dark) Color(0x30343D) else Color(0xFFFFFF)
        Row(
            modifier.clip(RoundedCornerShape(9.dp)).background(bg).border(1.dp, border, RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = if (dark) Color(0x8F9AAA) else Color(0x6E7781), style = TextStyle(fontSize = 10.sp))
            Spacer(Modifier.width(4.dp))
            Text(value, color = fg, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(3.dp))
            Text("⌄", color = border, style = TextStyle(fontSize = 11.sp))
        }
    }

    // ---------------- 消息区 ----------------

    @Composable
    private fun MessagesArea(modifier: Modifier, dark: Boolean) {
        val listState = rememberLazyListState()
        val totalItems = messages.size + if (isStreamingMsg.value) 1 else 0
        LaunchedEffect(totalItems, streamingText.value, streamingThinking.value, scrollRequest.value) {
            if (totalItems > 0) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                val nearBottom = lastVisible >= totalItems - 2
                if (scrollRequest.value > 0 || nearBottom || isStreamingMsg.value) {
                    listState.scrollToItem(totalItems - 1)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(messages.toList()) { _, msg ->
                MessageRow(msg, dark)
            }
            if (isStreamingMsg.value) {
                item(key = "streaming") {
                    StreamingRow(dark)
                }
            }
        }
    }

    @Composable
    private fun StreamingRow(dark: Boolean) {
        val msg = ChatMessage.assistant().also {
            it.appendThinking(streamingThinking.value)
            it.appendText(streamingText.value)
        }
        AssistantBubble(dark, msg.text, msg.thinking)
    }

    @Composable
    private fun MessageRow(msg: ChatMessage, dark: Boolean) {
        when (msg.kind) {
            ChatMessage.Kind.USER -> UserBubble(msg.text, dark)
            ChatMessage.Kind.ASSISTANT -> AssistantBubble(dark, msg.text, msg.thinking)
            ChatMessage.Kind.THINKING -> ThinkingBlock(msg.thinking, dark)
            ChatMessage.Kind.TOOL -> ToolCard(msg, dark)
            ChatMessage.Kind.SYSTEM -> SystemLine(msg.text, dark)
            ChatMessage.Kind.ERROR -> ErrorLine(msg.text, dark)
        }
    }

    @Composable
    private fun UserBubble(text: String, dark: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp))
                    .background(if (dark) Color(0x3A6AA5) else Color(0x4B8BF5))
                    .padding(horizontal = 15.dp, vertical = 11.dp)
            ) {
                PlainTextContent(text, Color(0xFFFFFF))
            }
        }
    }

    @Composable
    private fun AssistantBubble(dark: Boolean, text: String, thinking: String) {
        Column(Modifier.fillMaxWidth()) {
            if (thinking.isNotEmpty()) {
                ThinkingBlock(thinking, dark)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier
                        .widthIn(max = 760.dp)
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    if (text.isNotEmpty()) {
                        MarkdownContent(text, dark = dark)
                    }
                }
            }
        }
    }

    @Composable
    private fun ThinkingBlock(thinking: String, dark: Boolean) {
        var expanded by remember { mutableStateOf(false) }
        val fg = if (dark) Color(0x8A93A5) else Color(0x6E7781)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (dark) Color(0x2B2F38) else Color(0xF0F1F4))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "▾" else "▸", color = fg, style = TextStyle(fontSize = 11.sp))
                Spacer(Modifier.width(6.dp))
                Text("思考中…", color = fg, style = TextStyle(fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    thinking,
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    maxLines = 30
                )
            }
        }
    }

    @Composable
    private fun ToolCard(msg: ChatMessage, dark: Boolean) {
        var expanded by remember { mutableStateOf(false) }
        val fg = if (dark) Color(0xC9CDD4) else Color(0x3C4043)
        val sub = if (dark) Color(0x8A93A5) else Color(0x6E7781)
        val statusColor = when (msg.toolStatus) {
            "error" -> Color(0xE53935)
            "done" -> if (dark) Color(0x66BB6A) else Color(0x43A047)
            else -> Color(0xFB8C00)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (dark) Color(0x2B2F38) else Color(0xF0F1F4))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "▾" else "▸", color = sub, style = TextStyle(fontSize = 11.sp))
                Spacer(Modifier.width(6.dp))
                Text("🛠 ${msg.toolName}", color = fg, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    when (msg.toolStatus) {
                        "done" -> "完成"
                        "error" -> "错误"
                        else -> "运行中"
                    },
                    color = statusColor,
                    style = TextStyle(fontSize = 11.sp)
                )
            }
            if (expanded && (msg.toolResult.isNotEmpty() || msg.argsSummary.isNotEmpty())) {
                Spacer(Modifier.height(6.dp))
                if (msg.toolResult.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            msg.toolResult,
                            color = fg,
                            fontFamily = FontFamily.Monospace,
                            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                            maxLines = 40
                        )
                    }
                } else if (msg.argsSummary.isNotEmpty()) {
                    Text(
                        msg.argsSummary,
                        color = sub,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(fontSize = 11.sp),
                        maxLines = 20
                    )
                }
            }
        }
    }

    @Composable
    private fun SystemLine(text: String, dark: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text,
                color = if (dark) Color(0x8A93A5) else Color(0x9AA0A6),
                style = TextStyle(fontSize = 11.5.sp),
                maxLines = 3
            )
        }
    }

    @Composable
    private fun ErrorLine(text: String, dark: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (dark) Color(0x4A2527) else Color(0xFDECEA))
                    .padding(12.dp)
            ) {
                Text(
                    text,
                    color = Color(0xE53935),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
                )
            }
        }
    }

    // ---------------- 输入区 ----------------

    @Composable
    private fun InputArea(dark: Boolean) {
        val bg = if (dark) Color(0x292C34) else Color(0xFFFFFF)
        val composer = if (dark) Color(0x343840) else Color(0xF4F6F8)
        val border = if (dark) Color(0x4A515D) else Color(0xD4D8DD)
        val fg = if (dark) Color(0xE6EAF0) else Color(0x24292E)
        val sub = if (dark) Color(0x8F99AA) else Color(0x6E7781)
        Column(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(composer)
                    .border(1.dp, border, RoundedCornerShape(18.dp))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                ComposerAction("＋", dark) { }
                Spacer(Modifier.width(4.dp))
                ComposerAction("⌁", dark) { }
                Spacer(Modifier.width(5.dp))
                TextField(
                    value = inputText.value,
                    onValueChange = { inputText.value = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp, max = 130.dp)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                                // Do not consume Enter while an IME composition is
                                // active. Chinese/Japanese/Korean IMEs use Enter to
                                // commit the current candidate; consuming it here
                                // would send a half-composed message instead.
                                if (inputText.value.composition == null) {
                                    sendMessage()
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("输入消息…", color = sub) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() })
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (busy.value) Color(0xC65E62) else Color(0x4B8BF5))
                        .clickable { if (busy.value) abort() else sendMessage() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (busy.value) "■" else "↑", color = Color.White, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${currentModel.value?.name ?: "未选择模型"} · ${currentThinking.value ?: "off"}", color = sub, style = TextStyle(fontSize = 10.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(sendHint.value, color = sub, style = TextStyle(fontSize = 10.sp))
            }
        }
        Divider(Orientation.Horizontal, color = border, thickness = 1.dp)
    }

    @Composable
    private fun ComposerAction(label: String, dark: Boolean, onClick: () -> Unit) {
        Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, color = if (dark) Color(0xB8C0CC) else Color(0x6E7781), style = TextStyle(fontSize = 20.sp))
        }
    }

    // ---------------- 选择器弹窗 ----------------

    @Composable
    private fun OptionPicker(
        title: String,
        options: List<Pair<String, Boolean>>,
        onSelect: (String) -> Unit,
        onDismiss: () -> Unit,
        dark: Boolean
    ) {
        Dialog(onDismissRequest = onDismiss) {
            val fg = if (dark) Color(0xC9CDD4) else Color(0x3C4043)
            val bg = if (dark) Color(0x2B2D32) else Color(0xFFFFFF)
            val hover = if (dark) Color(0x3A3D44) else Color(0xF0F2F5)
            Column(
                Modifier
                    .widthIn(min = 260.dp, max = 420.dp)
                    .background(bg, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Text(
                    title,
                    color = fg,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
                if (options.isEmpty()) {
                    Text("（无可用选项）", color = if (dark) Color(0x8A93A5) else Color(0x9AA0A6), modifier = Modifier.padding(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(options) { _, (text, selected) ->
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (hovered) hover else bg)
                                .clickable(interactionSource = interaction, indication = null) { onSelect(text) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text,
                                color = fg,
                                style = TextStyle(fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                maxLines = 1
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected) Text("✓", color = if (dark) Color(0x8A9BFF) else Color(0x4B8BF5))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NewSessionDialog(dark: Boolean) {
        if (!showNewSessionDialog.value) return
        val fg = if (dark) Color(0xE6E8ED) else Color(0x24292E)
        val sub = if (dark) Color(0x9AA3B2) else Color(0x6E7781)
        val bg = if (dark) Color(0x2B2D32) else Color(0xFFFFFF)
        Dialog(onDismissRequest = { showNewSessionDialog.value = false }) {
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 480.dp)
                    .background(bg, RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Text("新建会话", color = fg, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                Text(
                    "新建会话将清空当前聊天，并开始一个全新的 pi 会话（终端里也可继续使用）。",
                    color = sub,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    org.jetbrains.jewel.ui.component.OutlinedSlimButton(onClick = { showNewSessionDialog.value = false }) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    org.jetbrains.jewel.ui.component.DefaultButton(onClick = { confirmNewSession() }) {
                        Text("新建")
                    }
                }
            }
        }
    }

    /**
     * Extension UI is rendered inside the Tool Window instead of using
     * JOptionPane. This keeps focus, theme and IME handling in the same Compose
     * surface as the chat input.
     */
    @Composable
    private fun ExtensionDialogOverlay(dark: Boolean) {
        val state = extensionDialog.value ?: return
        val req = state.request
        val fg = if (dark) Color(0xE6E8ED) else Color(0x24292E)
        val sub = if (dark) Color(0x9AA3B2) else Color(0x6E7781)
        val bg = if (dark) Color(0x2B2D32) else Color(0xFFFFFF)

        Dialog(onDismissRequest = { cancelExtension(req) }) {
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 560.dp)
                    .background(bg, RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Text(
                    req.title().ifEmpty { "pi 请求输入" },
                    color = fg,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                )
                if (req.message().isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(req.message(), color = sub, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
                }
                Spacer(Modifier.height(12.dp))

                when (req.method()) {
                    "select" -> {
                        val options = req.options()
                        if (options.isEmpty()) {
                            Text("（无可用选项）", color = sub, style = TextStyle(fontSize = 12.sp))
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                options.forEach { option ->
                                    org.jetbrains.jewel.ui.component.OutlinedSlimButton(
                                        onClick = { completeExtension(req, value = option) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(option, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            org.jetbrains.jewel.ui.component.OutlinedSlimButton(onClick = { cancelExtension(req) }) {
                                Text("取消")
                            }
                        }
                    }
                    "confirm" -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            org.jetbrains.jewel.ui.component.OutlinedSlimButton(onClick = { cancelExtension(req) }) {
                                Text("取消")
                            }
                            Spacer(Modifier.width(8.dp))
                            org.jetbrains.jewel.ui.component.OutlinedSlimButton(onClick = { completeExtension(req, confirmed = false) }) {
                                Text("否")
                            }
                            Spacer(Modifier.width(8.dp))
                            org.jetbrains.jewel.ui.component.DefaultButton(onClick = { completeExtension(req, confirmed = true) }) {
                                Text("是")
                            }
                        }
                    }
                    "input", "editor" -> {
                        var input by remember(req.id()) {
                            mutableStateOf(TextFieldValue(if (req.method() == "editor") req.prefill() else ""))
                        }
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp, max = if (req.method() == "editor") 260.dp else 120.dp),
                            placeholder = {
                                if (req.placeholder().isNotEmpty()) Text(req.placeholder(), color = sub)
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            org.jetbrains.jewel.ui.component.OutlinedSlimButton(onClick = { cancelExtension(req) }) {
                                Text("取消")
                            }
                            Spacer(Modifier.width(8.dp))
                            org.jetbrains.jewel.ui.component.DefaultButton(onClick = { completeExtension(req, value = input.text) }) {
                                Text("确定")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PickerDialogs(dark: Boolean) {
        if (showModelPicker.value) {
            OptionPicker(
                "选择模型",
                models.map { it.name to (it == currentModel.value) },
                onSelect = { name -> models.firstOrNull { it.name == name }?.let { selectModel(it) } },
                onDismiss = { showModelPicker.value = false },
                dark
            )
        }
        if (showThinkingPicker.value) {
            OptionPicker(
                "选择思考强度",
                thinkingLevels.map { it to (it == currentThinking.value) },
                onSelect = { selectThinking(it) },
                onDismiss = { showThinkingPicker.value = false },
                dark
            )
        }
        if (showSessionPicker.value) {
            OptionPicker(
                "选择会话",
                sessions.map { (if (it.isCurrent) "✓ " else "") + it.name to it.isCurrent },
                onSelect = { name -> sessions.firstOrNull { s -> (if (s.isCurrent) "✓ " else "") + s.name == name }?.let { selectSession(it) } },
                onDismiss = { showSessionPicker.value = false },
                dark
            )
        }
    }
}

/** 从会话文件名解析 pi 的 UUID id（形如 2026-08-21T19-24-10-173Z_01a025c7-...jsonl → 01a025c7-...）。 */
private fun parseSessionId(name: String): String {
    val m = Regex("_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.jsonl$").find(name)
    return m?.groupValues?.get(1) ?: name.removeSuffix(".jsonl")
}
