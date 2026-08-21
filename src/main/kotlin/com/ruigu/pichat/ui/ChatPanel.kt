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
import com.intellij.openapi.project.Project
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
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Pi Chat 主面板（Compose 版）。
 * 通过 [RpcClient] 与本地 pi 会话通信（共享 ~/.pi/agent/sessions/）。
 * 所有 Compose 状态只在 EDT 上修改（PiListener 回调经 invokeLater 转发）。
 */
class ChatPanel(private val project: Project) : Disposable, PiListener {

    private data class ModelItem(val provider: String, val id: String, val name: String, val contextWindow: Long = 0)
    private data class SessionItem(val path: String, val name: String, val isCurrent: Boolean, val title: String? = null)
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
                handleStateReady(data)
                val target = pendingSessionSwitch.remove(candidate) ?: return@onEdt
                candidate.switchSession(target).thenAccept { result ->
                    onEdt {
                        if (candidate !== client) return@onEdt
                        if (result != null && result.success()) {
                            clearMessages()
                            addSystem("已打开独立 Pi 会话")
                            refreshStatus()
                            loadHistory()
                            loadSessionList(target)
                        } else {
                            addSystem("打开会话失败: " + (result?.error() ?: "无响应"))
                        }
                    }
                }
            }
        }
        return candidate
    }

    // ================= 生命周期 =================

    private fun setupWebUi() {
        browserQuery.addHandler { raw ->
            onEdt { handleWebAction(raw) }
            null
        }

        val html = javaClass.getResourceAsStream("/web/index.html")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: "<html><body>Pi Chat UI resources were not found.</body></html>"
        val dark = com.intellij.util.ui.UIUtil.isUnderDarcula()
        val bootstrap = """
            <script>
              window.__INITIAL_IDE_THEME__ = '${if (dark) "dark" else "light"}';
              window.__INITIAL_TAB_PROVIDER__ = 'pi';
              window.__INITIAL_TAB_MODEL__ = '';
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

        // Keep JBCefJSQuery's generated bridge code out of the HTML document.
        // It can contain script-sensitive text and corrupt a single-file Vite bundle,
        // which makes Chromium display the minified JavaScript as page text. This is
        // also how jetbrains-cc-gui installs its bridge: after the main frame loads.
        val bridgeCall = browserQuery.inject("JSON.stringify({type:'bridge',payload:String(payload)})")
        browser.jbCefClient.cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val runtimeBootstrap = """
                    window.__CCG_PAGE_GENERATION__ = 1;
                    window.__CCGUI_PAGE_CONTEXT_READY__ = true;
                    window.__CCGUI_PAGE_LOAD_KIND__ = 'initial_load';
                    window.__CCGUI_RECOVERY_RELOAD__ = false;
                    window.sendToJava = function(payload) { $bridgeCall; };
                    if (typeof window.__ccgOnBridgeReady === 'function') window.__ccgOnBridgeReady();
                """.trimIndent()
                cefBrowser.executeJavaScript(runtimeBootstrap, cefBrowser.url, 0)
            }
        })
        browser.loadHTML(htmlWithInitialState)
    }

    private fun handleWebAction(raw: String) {
        val action = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            return
        }
        when (action.str("type")) {
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
                sendMessage(text)
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
                sessions.firstOrNull { it.path == id }?.let { selectSession(it) }
            }
            "delete_session" -> deleteSession(content)
            "delete_sessions" -> deleteSessions(content)
            "update_title" -> updateSessionTitle(content)
            "get_context_presets" -> publishContextPresets()
            "set_context_preset" -> handleSetContextPreset(content)
            "export_session", "toggle_favorite",
            "convert_to_cli_session", "create_new_tab" ->
                callWeb("addToast", "该交互将在下一阶段接入 Pi", "info")
        }
    }

    private fun publishDependencyStatus() {
        callWeb("updateDependencyStatus", "{}")
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
                    addProperty("title", session.title ?: session.name.removeSuffix(".jsonl"))
                    addProperty("messageCount", if (session.isCurrent) messages.size else 0)
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
        }
    }

    private fun webMessages(): JsonArray = JsonArray().also { result ->
        messages.forEach { message ->
            if (message.kind == ChatMessage.Kind.TOOL) {
                result.add(toolUseMessage(message))
                if (message.toolStatus != "running" || message.toolResult.isNotBlank()) {
                    result.add(toolResultMessage(message))
                }
            } else {
                result.add(messageJson(message))
            }
        }
        if (isStreamingMsg.value && (streamingText.value.isNotBlank() || streamingThinking.value.isNotBlank())) {
            result.add(assistantMessage(streamingText.value, streamingThinking.value, true))
        }
    }

    private fun messageJson(message: ChatMessage): JsonObject = when (message.kind) {
        ChatMessage.Kind.USER -> basicMessage("user", message.text)
        ChatMessage.Kind.ASSISTANT -> assistantMessage(message.text, message.thinking, false)
        ChatMessage.Kind.THINKING -> assistantMessage("", message.thinking.ifBlank { message.text }, false)
        ChatMessage.Kind.SYSTEM -> basicMessage("notification", message.text)
        ChatMessage.Kind.ERROR -> basicMessage("error", message.text)
        ChatMessage.Kind.TOOL -> toolUseMessage(message)
    }

    private fun basicMessage(type: String, text: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("content", text)
        addProperty("timestamp", Instant.now().toString())
        add("raw", JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
            })
        })
    }

    private fun assistantMessage(text: String, thinking: String, streaming: Boolean): JsonObject = JsonObject().apply {
        addProperty("type", "assistant")
        addProperty("content", text)
        addProperty("isStreaming", streaming)
        addProperty("timestamp", Instant.now().toString())
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
        addProperty("timestamp", Instant.now().toString())
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
        addProperty("timestamp", Instant.now().toString())
        add("raw", JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", message.toolCallId ?: "tool-${message.timestamp}")
                    addProperty("content", message.toolResult)
                    addProperty("is_error", message.toolStatus == "error")
                })
            })
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

    private fun handleStateReady(data: JsonObject) {
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
        loadHistory()
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

    private fun sendMessage(textOverride: String? = null) {
        val text = (textOverride ?: inputText.value.text).trim()
        if (text.isEmpty() || !client.isRunning()) return
        System.out.println("[PiChat] send: " + text.take(50))
        inputText.value = TextFieldValue("")
        messages.add(ChatMessage.user(text))
        scrollRequest.value++
        publishWebState()
        if (streaming) {
            client.promptSteer(text)
        } else {
            client.prompt(text)
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
        return "● $phase$tail$balanceTail"
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
        client.getAvailableModels().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("models")) return@onEdt
                val arr = res.data().getAsJsonArray("models")
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

    private fun loadHistory() {
        client.getMessages().thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("messages")) return@onEdt
                if (messages.isNotEmpty()) return@onEdt
                val arr = res.data().getAsJsonArray("messages")
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val m = el.asJsonObject
                    if (!m.has("role")) continue
                    when (m.get("role").asString) {
                        "user" -> messages.add(ChatMessage.user(textOf(m.get("content"))))
                        "assistant" -> {
                            val am = ChatMessage.assistant()
                            val content = m.get("content")
                            if (content != null && content.isJsonArray) {
                                for (b in content.asJsonArray) {
                                    if (!b.isJsonObject) continue
                                    val block = b.asJsonObject
                                    val t = block.str("type")
                                    if (t == "text" && block.has("text")) am.appendText(block.get("text").asString)
                                    else if (t == "thinking" && block.has("thinking")) am.appendThinking(block.get("thinking").asString)
                                }
                            } else if (content != null && content.isJsonPrimitive) {
                                am.appendText(content.asString)
                            }
                            if (!am.isEmpty) messages.add(am)
                        }
                        "toolResult" -> {
                            val name = m.str("toolName") ?: "tool"
                            val id = m.str("toolCallId") ?: ""
                            val tm = ChatMessage.tool(id, name, "")
                            tm.toolStatus = "done"
                            tm.toolResult = textOf(m.get("content"))
                            messages.add(tm)
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
                if (messages.isNotEmpty()) {
                    addSystem("已恢复会话，共 ${messages.size} 条历史消息（与终端 pi 共享）")
                }
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
                        list.add(SessionItem(p.toString(), name, name == currentName, readSessionTitle(p)))
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
        val currentK = (model?.contextWindow?.div(1000))?.toInt() ?: 0
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
        onEdt {
            callWeb("updateContextPresets", gson.toJson(JsonObject().apply {
                addProperty("currentK", currentK)
                addProperty("persistedK", persistedK)
                add("presets", JsonArray().also { arr -> intArrayOf(200, 400, 1000).forEach { arr.add(it) } })
                model?.let { addProperty("modelKey", "${it.provider}/${it.id}") }
            }))
        }
    }

    /** 设置挡位：写 ctx-preset.json（扩展 session_start 时自动应用）→ 重启 pi 进程。 */
    private fun handleSetContextPreset(content: String) {
        val level = content.trim().toIntOrNull()
        val tokens = when (level) {
            200 -> 200_000L
            400 -> 400_000L
            1000 -> 1_000_000L
            else -> {
                callWeb("addToast", "可用挡位: 200 / 400 / 1000", "error")
                return
            }
        }
        val model = currentModel.value ?: run {
            callWeb("addToast", "当前没有活动模型", "warning")
            return
        }
        val key = "${model.provider}/${model.id}"
        try {
            val file = ctxPresetFile()
            val cfg = if (Files.exists(file)) {
                JsonParser.parseString(Files.readString(file)).asJsonObject
            } else {
                JsonObject()
            }
            val presets = if (cfg.has("presets") && cfg.get("presets").isJsonObject) {
                cfg.getAsJsonObject("presets")
            } else {
                JsonObject().also { cfg.add("presets", it) }
            }
            presets.addProperty(key, tokens)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(cfg), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            callWeb("addToast", "已设置 $key 上下文挡位 = ${level}k，正在重启会话…", "success")
            restartPiSession()
        } catch (e: Exception) {
            callWeb("addToast", "设置失败: ${e.message}", "error")
        }
    }

    /** 单会话模式：关掉当前进程，重启并恢复当前会话（ctx 挡位在 session_start 时生效）。 */
    private fun restartPiSession() {
        val current = currentSessionFile.value
        client.close()
        val next = createClient(client.cwd)
        if (current.isNotBlank()) pendingSessionSwitch[next] = current
        client = next
        clearMessages()
        currentSessionFile.value = ""
        connected.value = false
        statusText.value = "● 连接中…"
        startClient(next)
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
        streamingAssistant = ChatMessage.assistant()
        onEdt {
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
            if (m != null && partialResult != null && partialResult.has("content")) {
                m.toolResult = textFromContent(partialResult.get("content"))
                publishWebState()
            }
        }
    }

    override fun onToolEnd(toolCallId: String, toolName: String, isError: Boolean, result: JsonObject?) {
        onEdt {
            val m = findToolMessage(toolCallId) ?: ChatMessage.tool(toolCallId, toolName, "").also { messages.add(it) }
            m.toolStatus = if (isError) "error" else "done"
            if (result != null && result.has("content")) {
                m.toolResult = textFromContent(result.get("content"))
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
            statusText.value = "● 正在回复…$statsTail$balanceTail"
            publishWebState()
            loadSessionStats()
        }
    }

    override fun onAgentEnd(agentMessages: JsonArray?, willRetry: Boolean) {
        onEdt {
            val sa = streamingAssistant
            if (sa != null) {
                // 优先用 agent_end 携带的完整助手消息（更可靠），否则用流式累积的内容
                val full = agentMessages?.let { extractAssistantContent(it) }
                if (full != null) {
                    sa.setText(full.first)
                    sa.setThinking(full.second)
                } else {
                    val t = streamingText.value
                    val th = streamingThinking.value
                    if (t.isNotEmpty()) sa.appendText(t)
                    if (th.isNotEmpty()) sa.appendThinking(th)
                }
                if (sa.isEmpty) {
                    messages.remove(sa)
                } else {
                    messages.add(sa)
                }
                streamingAssistant = null
                isStreamingMsg.value = false
                streamingText.value = ""
                streamingThinking.value = ""
            }
            publishWebState()
        }
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
        }
    }

    override fun onQueueUpdate(queue: JsonObject) {
        val steering = if (queue.has("steering") && queue.get("steering").isJsonArray) queue.getAsJsonArray("steering").size() else 0
        val followUp = if (queue.has("followUp") && queue.get("followUp").isJsonArray) queue.getAsJsonArray("followUp").size() else 0
        val total = steering + followUp
        onEdt {
            queueCount.value = total
            statusText.value = when {
                busy.value -> "● 正在回复…$statsTail$balanceTail"
                total > 0 -> "● 队列:$total$statsTail$balanceTail"
                else -> "● 已连接$statsTail$balanceTail"
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

    /** 处理扩展 setStatus：仅关心 provider-balance（套餐余额），追加到状态栏文本尾部。 */
    private fun handleSetStatus(req: ExtensionUiRequest) {
        val key = req.raw().str("statusKey")
        if (key != "provider-balance") return
        balanceText = req.raw().str("statusText")
        statusText.value = when {
            busy.value -> "● 正在回复…$statsTail$balanceTail"
            queueCount.value > 0 -> "● 队列:${queueCount.value}$statsTail$balanceTail"
            else -> "● 已连接$statsTail$balanceTail"
        }
        publishWebState()
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
