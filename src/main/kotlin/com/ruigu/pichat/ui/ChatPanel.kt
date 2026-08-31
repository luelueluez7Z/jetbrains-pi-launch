package com.ruigu.pichat.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import com.ruigu.pichat.ide.IdeIntegrator
import com.ruigu.pichat.rpc.ExtensionUiRequest
import com.ruigu.pichat.rpc.PiListener
import com.ruigu.pichat.rpc.RpcClient
import com.ruigu.pichat.session.SessionItem
import com.ruigu.pichat.session.SessionRegexes
import com.ruigu.pichat.session.applySessionMessage
import com.ruigu.pichat.session.appendSessionInfo
import com.ruigu.pichat.session.finalizeInterruptedTools
import com.ruigu.pichat.session.scanSessionDirectory
import com.ruigu.pichat.session.textOf
import com.ruigu.pichat.session.isStrayThinkingTag
import com.ruigu.pichat.session.parseSessionId
import com.ruigu.pichat.session.readLeafId
import com.ruigu.pichat.session.readSessionFile
import com.ruigu.pichat.session.readSessionTitle
import com.ruigu.pichat.session.stripMagicContextMarks
import com.ruigu.pichat.session.stripThinkingTags
import com.ruigu.pichat.session.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

    @Volatile
    private lateinit var client: RpcClient
    /** 单会话模式：同时只保留一个 pi 进程（与终端 pi 一致），切换会话即重启进程 */
    private val pendingSessionSwitch = IdentityHashMap<RpcClient, String>()

    /** 流式进行中标记：RPC 读线程写（onAgentStart/onAgentSettled）、EDT 读（sendMessage 决定 steer/followUp），需要原子可见。 */
    private val streaming = AtomicBoolean(false)

    /** 当前流式占位消息：RPC 读线程创建/判断、EDT 落定置空，需 volatile 保证跨线程可见。 */
    @Volatile
    private var streamingAssistant: ChatMessage? = null

    // ================= 状态（EDT 修改，经 publishWebState 推送到 webview） =================

    private var statusText = "● 连接中…"
    private var statusTip = ""
    private var queueCount = 0
    private var connected = false
    private val messages = mutableListOf<ChatMessage>()
    private var isStreamingMsg = false
    private var streamingText = ""
    private var streamingThinking = ""
    private var busy = false

    private val models = mutableListOf<ModelItem>()
    private var currentModel: ModelItem? = null
    /** Pi's session model scope, resolved from global/project enabledModels settings. */
    private val modelScopePatterns: List<String>? by lazy {
        val agentDir = System.getenv("PI_CODING_AGENT_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".pi", "agent")
        val global = agentDir.resolve("settings.json")
        val projectSettings = project.basePath?.let { Path.of(it).resolve(".pi").resolve("settings.json") }
        PiModelScope.loadPatterns(global, projectSettings)
    }
    private val thinkingLevels = mutableListOf<String>()
    private var currentThinking: String? = null
    private val sessions = mutableListOf<SessionItem>()
    private var currentSessionFile = ""

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
                    candidate.switchSession(target).logFailure("switch_session").thenAccept { result ->
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

    private val ide = IdeIntegrator(project) { msg -> callWeb("addToast", msg, "error") }

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
            "open_file" -> ide.openFile(content)
            "show_diff" -> ide.showDiff(content)
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
            currentModel?.let { addProperty("defaultModel", modelKey(it)) }
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

    /**
     * Pi 的内置 @ 补全通过 fd 扫描文件。插件优先复用 Pi 管理目录中的 fd，
     * 找不到时再尝试系统 PATH；fd 启动失败后回退到原有 Kotlin 扫描实现。
     */
    private val fdExecutable: String? by lazy { resolveFdExecutable() }
    @Volatile private var fdDisabled = false

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

    /** 定位 Pi 管理的 fd 或系统 PATH 中的 fd 命令名。 */
    private fun resolveFdExecutable(): String? {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val executableName = if (isWindows) "fd.exe" else "fd"
        val agentDir = System.getenv("PI_CODING_AGENT_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".pi", "agent")
        val managed = agentDir.resolve("bin").resolve(executableName)
        if (Files.isRegularFile(managed)) return managed.toString()

        // ProcessBuilder resolves bare names through PATH, so do not invoke a shell here.
        return if (isWindows) "fd.exe" else "fd"
    }

    /**
     * 使用 Pi 内置 autocomplete 相同的 fd 参数搜索文件。
     * 返回 null 表示 fd 不可用/执行失败，调用方应回退旧扫描；空列表表示确实没有匹配项。
     */
    private fun searchFilesWithFd(root: Path, currentPath: String, query: String): List<FileEntry>? {
        if (fdDisabled) return null
        val executable = fdExecutable ?: return null
        val relativeCurrent = currentPath.trim('/').replace('\\', '/').trim('/')
        val searchRoot = if (relativeCurrent.isEmpty()) root else root.resolve(relativeCurrent).normalize()
        if (!searchRoot.startsWith(root) || !Files.isDirectory(searchRoot)) return emptyList()

        val args = mutableListOf(
            "--base-directory", searchRoot.toString(),
            "--max-results", "100",
            "--type", "f",
            "--type", "d",
            "--follow",
            "--hidden",
            "--exclude", ".git",
            "--exclude", ".git/*",
            "--exclude", ".git/**",
        )
        if (query.isNotBlank()) {
            // 查询词来自 JSON，不经过 shell；-- 还可避免以 - 开头的输入被当成 fd 选项。
            args += "--"
            args += query
        }

        val process = try {
            ProcessBuilder(listOf(executable) + args)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            fdDisabled = true
            LOG.warn("[PiChatDiag] fd unavailable; falling back to local file scan", e)
            return null
        }

        val lines = try {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
        } catch (e: Exception) {
            LOG.warn("[PiChatDiag] failed to read fd output; falling back to local file scan", e)
            return null
        }
        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        // fd uses exit code 1 for a valid search with no matches. Any higher
        // exit code is an invocation/error message (stderr is merged above),
        // so never expose that text as a fake file path.
        if (exitCode > 1) {
            LOG.warn("[PiChatDiag] fd exited with code $exitCode; falling back to local file scan")
            return null
        }

        val prefix = if (relativeCurrent.isEmpty()) "" else "$relativeCurrent/"
        return lines.mapNotNull { raw ->
            val display = raw.trim().replace('\\', '/')
            if (display.isEmpty()) return@mapNotNull null
            val isDirectory = display.endsWith('/')
            val clean = display.trimEnd('/')
            if (clean.isEmpty()) return@mapNotNull null
            val rel = (prefix + clean).replace("//", "/")
            val absolute = root.resolve(rel).normalize()
            if (!absolute.startsWith(root)) return@mapNotNull null
            val name = absolute.fileName?.toString() ?: return@mapNotNull null
            val directory = isDirectory || Files.isDirectory(absolute)
            val ext = if (directory) "" else name.substringAfterLast('.', "").takeIf { it != name } ?: ""
            FileEntry(name, rel, absolute.toString(), if (directory) "directory" else "file", ext)
        }
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

        val start = if (currentPath.isEmpty()) base else base.resolve(currentPath).normalize()
        if (!start.startsWith(base) || !Files.isDirectory(start)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            // fd 输出已经按 currentPath 限定；仅 fd 不可用时回退旧的全量扫描。
            val entries = searchFilesWithFd(base, currentPath, req.str("query").trim())
                ?: scanProjectFiles(base).filter { entry ->
                    val abs = Path.of(entry.abs).normalize()
                    abs.startsWith(start) && abs != start
                }
            val rows = JsonArray()
            for (e in entries) {
                rows.add(JsonObject().apply {
                    addProperty("name", e.name)
                    addProperty("path", e.relPath)
                    addProperty("absolutePath", e.abs)
                    addProperty("type", e.type)
                    if (e.ext.isNotEmpty()) addProperty("extension", e.ext)
                })
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
                    currentModel?.let { addProperty("model", it.id) }
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
            val status = statusText.removePrefix("● ")
            if (status != webStatusSent) {
                webStatusSent = status
                callWeb("updateStatus", status)
            }
            callWeb("showLoading", busy)
            if (currentSessionFile.isNotBlank()) callWeb("setSessionId", currentSessionFile)
            currentModel?.let { model ->
                callWeb("onModelConfirmed", modelKey(model), "pi")
            }
            callWeb("applyBackendTabState", gson.toJson(JsonObject().apply {
                addProperty("provider", "pi")
                currentModel?.let { addProperty("model", modelKey(it)) }
                addProperty("permissionMode", "default")
                addProperty("reasoningEffort", currentThinking ?: "off")
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
            if (message === streamingAssistant && isStreamingMsg) {
                // 流式占位消息：用实时累积的文本渲染，保持消息在会话中的正确顺序
                // （否则末尾追加会导致后续工具消息排在 assistant 之前）
                result.add(assistantMessage(
                    streamingText, streamingThinking, true, message.getTimestamp()))
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
                        // argsSummary 解析结果缓存在 ChatMessage 里（每次 publishWebState 都会调到这里）
                        add("input", message.getArgsAsJson())
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
                    connected = false
                    statusText = "✗ 未连接"
                    messages.add(ChatMessage.error(msg))
                    publishWebState()
                }
            }
        }
    }

    private fun handleStateReady(data: JsonObject, loadHistory: Boolean = true) {
        LOG.info("[PiChatDiag] handleStateReady: " + data)
        connected = true
        statusText = "● 已连接"
        val sessionFile = data.str("sessionFile")
        currentSessionFile = sessionFile
        statusTip = (if (sessionFile.isNotEmpty()) "会话文件: $sessionFile\n" else "") + "工作目录: ${client.cwd}"
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
        publishWebState()
    }

    override fun dispose() {
        // dispose 在 EDT 触发；进程销毁异步化避免工具窗口关闭时卡顿
        client.closeAsync()
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
        isStreamingMsg = false
        streamingText = ""
        streamingThinking = ""
        publishWebState()
    }

    // ================= 动作 =================

    private fun sendMessage(textOverride: String, behavior: String = "steer", images: List<JsonObject> = emptyList()) {
        val text = textOverride.trim()
        if (text.isEmpty() || !client.isRunning()) return
        LOG.info("[PiChatDiag] send: " + text.take(50))
        messages.add(ChatMessage.user(text))
        publishWebState()
        if (streaming.get()) {
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
        busy = false
        addSystem("⏹ 已请求停止")
    }

    private fun confirmNewSession() {
        // 单会话模式：先停掉旧进程，再启动新会话进程（与终端 pi 一致）。
        // closeAsync：EDT 上不能等 waitFor(2s)，否则切换会话时 UI 冻结。
        client.closeAsync()
        val next = createClient(client.cwd)
        client = next
        clearMessages()
        currentSessionFile = ""
        connected = false
        statusText = "● 连接中…"
        startClient(next)
    }

    private fun refreshStatus() {
        client.getState().logFailure("get_state").thenAccept { res ->
            onEdt {
                if (res != null && res.success() && res.data() != null) {
                    val d = res.data()
                    val sessionFile = d.str("sessionFile")
                    currentSessionFile = sessionFile
                    statusText = "● 已连接"
                    statusTip = (if (sessionFile.isNotEmpty()) "会话文件: $sessionFile\n" else "") + "工作目录: ${client.cwd}"
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
        client.getSessionStats().logFailure("get_session_stats").thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null) return@onEdt
                statusText = formatPiStatus(res.data())
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
        // 累计费用（pi getSessionStats 的 cost 字段，跨全部会话条目含压缩历史；与 TUI footer 的 $x.xxx 显示一致）
        val cost = if (data.has("cost") && data.get("cost").isJsonPrimitive) data.get("cost").asDouble else 0.0
        val phase = if (busy) "正在回复…" else "空闲"
        val contextPart = if (max > 0) "${formatTokenCount(used)}/${formatTokenCount(max)} (${formatPercent(percent)})" else "${formatTokenCount(used)}"
        // cost > 0 才显示（如 $0.123），与 TUI 对齐
        val costPart = if (cost > 0) " · \$" + String.format(java.util.Locale.ROOT, "%.3f", cost) else ""
        val tail = " · $contextPart · cache ${formatPercent(cachePercent)} · ↑${formatTokenCount(output)} ↓${formatTokenCount(input)}$costPart"
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
        val sameModel = currentModel?.let { it.provider == item.provider && it.id == item.id } == true
        client.setModel(item.provider, item.id).logFailure("set_model").thenAccept { res ->
            onEdt {
                if (res != null && res.success()) {
                    currentModel = item
                    if (!sameModel) addSystem("已切换模型: ${item.name}")
                    loadThinkingLevels("")
                } else {
                    addSystem("切换模型失败: " + (res?.error() ?: "无响应"))
                }
            }
        }
    }

    private fun selectThinking(level: String) {
        client.setThinkingLevel(level).logFailure("set_thinking_level").thenAccept { res ->
            onEdt {
                if (res != null && res.success()) {
                    currentThinking = level
                    addSystem("已切换思考强度: $level")
                } else {
                    addSystem("切换思考强度失败: " + (res?.error() ?: "无响应"))
                }
            }
        }
    }

    private fun selectSession(item: SessionItem) {
        if (item.isCurrent) return

        // 单会话模式：先停掉当前 pi 进程，再启动新进程恢复目标会话。
        // closeAsync：EDT 上不能等 waitFor(2s)，否则切换会话时 UI 冻结。
        client.closeAsync()
        val next = createClient(client.cwd)
        pendingSessionSwitch[next] = item.path
        client = next
        clearMessages()
        currentSessionFile = ""
        connected = false
        statusText = "● 连接中…"
        startClient(next)
    }

    // ================= 数据加载 =================

    private fun loadModels(currentProvider: String, currentId: String) {
        LOG.info("[PiChatDiag] loadModels(provider=" + currentProvider + ", id=" + currentId + ")")
        client.getAvailableModels().logFailure("get_available_models").thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("models")) {
                    LOG.warn("[PiChatDiag] get_available_models 失败: " + (if (res == null) "null" else res.toString()))
                    return@onEdt
                }
                val arr = res.data().getAsJsonArray("models")
                LOG.info("[PiChatDiag] get_available_models OK, count=" + arr.size() + ", scope=" + modelScopePatterns)
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
                val allowedKeys = PiModelScope.resolveAllowedKeys(
                    list.map { PiModelScope.ModelRef(it.provider, it.id) },
                    modelScopePatterns,
                )
                val byKey = list.associateBy { "${it.provider}\u0000${it.id}" }
                val scopedList = allowedKeys.mapNotNull { byKey[it] }
                LOG.info("[PiChatDiag] model scope applied: available=" + list.size + ", selectable=" + scopedList.size)
                models.clear()
                models.addAll(scopedList)
                // 选中当前模型
                val cur = scopedList.firstOrNull { it.provider == currentProvider && it.id == currentId }
                currentModel = cur ?: scopedList.firstOrNull()
                publishWebState()
                // Replace the web UI's static fallback catalog with Pi's live
                // get_available_models snapshot once the RPC response arrives.
                publishModels()
            }
        }
    }

    private fun loadThinkingLevels(currentLevel: String) {
        client.getThinkingLevels().logFailure("get_available_thinking_levels").thenAccept { res ->
            onEdt {
                if (res == null || !res.success() || res.data() == null || !res.data().has("levels")) return@onEdt
                val arr = res.data().getAsJsonArray("levels")
                val list = mutableListOf<String>()
                for (el in arr) {
                    if (el.isJsonPrimitive) list.add(el.asString)
                }
                val prev = currentThinking
                thinkingLevels.clear()
                thinkingLevels.addAll(list)
                currentThinking = when {
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
        if (m != null) currentModel = m
    }

    private fun syncThinkingSelection(level: String) {
        if (level.isNotEmpty() && thinkingLevels.contains(level)) currentThinking = level
    }

    private fun loadHistory(force: Boolean = false) {
        // 优先直接读会话文件：会话文件与终端 pi / magic-context 共享，外部写入的最新消息
        // 只能通过读文件拿到（get_messages 返回的是本进程内存快照，会落后）。
        // 文件可能很大，读/解析都在后台线程执行，避免 EDT 卡顿。
        val file = currentSessionFile
        if (file.isNotBlank()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val parsed = readSessionFile(file)
                if (parsed == null) {
                    LOG.warn("[PiChatDiag] loadHistory 读会话文件失败，回退 get_messages: " + file)
                    loadHistoryFromRpc(force)
                    return@executeOnPooledThread
                }
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
            }
            return
        }
        loadHistoryFromRpc(force)
    }

    /** get_messages 兑底路径：文件读取失败/无会话文件时从 pi 进程内存快照加载。 */
    private fun loadHistoryFromRpc(force: Boolean) {
        client.getMessages().logFailure("get_messages").thenAccept { res ->
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
        isStreamingMsg = false
        streamingText = ""
        streamingThinking = ""
    }



    private fun loadSessionList(currentFile: String) {
        if (currentFile.isBlank()) return
        // 扫描目录 + 逐文件读元信息（readSessionMeta 全量读每个 jsonl）都是磁盘 IO，
        // 放到后台线程执行，结果回 EDT 刷新列表（此前在 EDT 执行是大会话列表 UI 假死的根因）。
        ApplicationManager.getApplication().executeOnPooledThread {
            val dir = try {
                Path.of(currentFile).parent
            } catch (e: Exception) {
                null
            } ?: return@executeOnPooledThread
            val currentName = try {
                Path.of(currentFile).fileName.toString()
            } catch (e: Exception) {
                ""
            }
            val list = try {
                scanSessionDirectory(dir, currentName)
            } catch (e: Exception) {
                LOG.warn("[PiChatDiag] loadSessionList 扫描失败: " + dir + " - " + e.message)
                emptyList()
            }
            onEdt {
                sessions.clear()
                sessions.addAll(list)
                publishWebState()
                // sessions 更新完成后立即推送历史数据（标题/删除后列表才能刷新）
                publishHistoryData()
            }
        }
    }

    // ================= 删除会话 =================

    private fun sessionDir(): Path? {
        return try {
            Path.of(currentSessionFile).parent
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
        // 文件删除可能被占用而阻塞，移到后台线程，结果回 EDT 提示 + 刷新列表
        ApplicationManager.getApplication().executeOnPooledThread {
            val deleted = try {
                Files.deleteIfExists(target)
            } catch (e: Exception) {
                LOG.warn("[PiChatDiag] deleteSession 失败: " + id + " - " + e.message)
                false
            }
            onEdt {
                // 成功时前端已乐观弹提示，这里只在失败时提示，避免重复
                if (!deleted) callWeb("addToast", "删除失败：文件可能被占用", "error")
                loadSessionList(currentSessionFile)
            }
        }
    }

    private fun deleteSessions(content: String) {
        val ids = try {
            val arr = JsonParser.parseString(content).asJsonArray
            arr.mapNotNull { el -> if (el.isJsonPrimitive) el.asString else null }
        } catch (e: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) return
        // 批量文件删除移到后台线程（同 deleteSession）
        ApplicationManager.getApplication().executeOnPooledThread {
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
                    LOG.warn("[PiChatDiag] deleteSessions 单项失败: " + id + " - " + e.message)
                    fail++
                }
            }
            onEdt {
                // 成功时前端已乐观弹提示，这里只在失败时提示，避免重复
                if (ok == 0 && fail > 0) callWeb("addToast", "删除失败：$fail 个会话未能删除", "error")
                else if (fail > 0) callWeb("addToast", "已删除 $ok 个，$fail 个失败", "warning")
                loadSessionList(currentSessionFile)
            }
        }
    }

    // ================= 修改会话标题 =================


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
        val clean = title.replace(SessionRegexes.NEWLINE_RUN, " ").trim()
        if (clean.isEmpty()) {
            callWeb("addToast", "标题不能为空", "error")
            return
        }
        // 读全量文件取 leafId + 追加写都是磁盘 IO，移到后台线程（此前在 EDT 执行）
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 镜像 pi TUI 的 renameSession：追加 session_info 记录（见 SessionFileStore.appendSessionInfo）
                appendSessionInfo(target, clean)
                onEdt {
                    callWeb("addToast", "标题已更新", "success")
                    loadSessionList(currentSessionFile)
                }
            } catch (e: Exception) {
                LOG.warn("[PiChatDiag] updateSessionTitle 失败: " + sessionId + " - " + e.message)
                onEdt { callWeb("addToast", "更新标题失败：${e.message}", "error") }
            }
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





    private fun JsonObject.str(key: String): String {
        return if (has(key) && !get(key).isJsonNull) get(key).asString else ""
    }

    private fun onEdt(fn: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { fn() }
    }

    /**
     * 给 RPC future 补异常日志：此前 thenAccept 链异常时无声丢失（表现为"点了没反应"）。
     * 返回原 future，可直接链 thenAccept。
     */
    private fun <T> java.util.concurrent.CompletableFuture<T>.logFailure(op: String): java.util.concurrent.CompletableFuture<T> = also {
        it.exceptionally { err ->
            LOG.warn("[PiChatDiag] " + op + " 失败: " + err.message)
            null
        }
    }

    // ================= PiListener（RPC 线程 → EDT） =================

    override fun onMessageUpdate(update: JsonObject) {
        if (!update.has("assistantMessageEvent") || !update.get("assistantMessageEvent").isJsonObject) return
        val ame = update.getAsJsonObject("assistantMessageEvent")
        val type = ame.str("type")
        // delta 字段防御：pi 事件异常时缺 delta 或非字符串，丢弃本次增量而不是抛异常（会被 fire() 吞掉导致流式中断）
        fun delta(): String? =
            ame.get("delta")?.takeIf { it.isJsonPrimitive && it.asString.isNotEmpty() }?.asString
        when (type) {
            "text_start", "thinking_start" -> ensureStreamingAssistant()
            "text_delta" -> {
                ensureStreamingAssistant()
                val d = delta() ?: return
                onEdt {
                    streamingText += d
                    publishWebState()
                }
            }
            "thinking_delta" -> {
                ensureStreamingAssistant()
                val d = delta() ?: return
                onEdt {
                    streamingThinking += d
                    publishWebState()
                }
            }
        }
    }

    /**
     * 确保流式占位消息存在。
     * 判断与创建必须都在 EDT 内完成：RPC 读线程连续收到两个 delta 时，
     * 若在 EDT 外先判断再 onEdt 占位，第二个 delta 会重复创建占位消息（出现两个“正在回复”气泡）。
     */
    private fun ensureStreamingAssistant() {
        if (streamingAssistant != null) return
        onEdt {
            // 双重检查：第一次占位的 onEdt 执行前，后续 delta 调用可能已进入此 lambda
            if (streamingAssistant != null) return@onEdt
            val sa = ChatMessage.assistant()
            streamingAssistant = sa
            // 占位：流式消息按事件顺序插入 messages，保证后续工具消息排在其后
            messages.add(sa)
            isStreamingMsg = true
            streamingText = ""
            streamingThinking = ""
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
            if (m.kind == ChatMessage.Kind.TOOL && toolCallId == m.toolCallId) return m
        }
        return null
    }

    override fun onAgentStart() {
        streaming.set(true)
        onEdt {
            busy = true
            // 保留统计尾部，不被纯 working 覆盖；随后异步刷新最新统计
            statusText = "● 正在回复…$statsTail$planTail$balanceTail"
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
            val t = streamingText
            val th = streamingThinking
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
        isStreamingMsg = false
        streamingText = ""
        streamingThinking = ""
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
        streaming.set(false)
        onEdt {
            busy = false
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
            queueCount = total
            statusText = when {
                busy -> "● 正在回复…$statsTail$planTail$balanceTail"
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
                    // plugin.xml 已注册 notificationGroup（id="Pi Chat"），直接取；不再用废弃的构造器兜底
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Pi Chat")
                        .createNotification(text.take(500), type)
                        .notify(project)
                }
            }
            "select" -> forwardExtensionDialog(req)
            "confirm" -> forwardExtensionDialog(req)
            "input" -> forwardExtensionDialog(req)
            "editor" -> forwardExtensionDialog(req)
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
            else -> return
        }
        statusText = when {
            busy -> "● 正在回复…$statsTail$planTail$balanceTail"
            queueCount > 0 -> "● 队列:${queueCount}$statsTail$planTail$balanceTail"
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
            connected = false
            statusText = "✗ 已断开"
            busy = false
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
        client.respondExtensionUi(req.id()) { o -> o.addProperty("cancelled", true) }
        publishWebState()
    }

    private fun completeExtension(req: ExtensionUiRequest, value: String? = null, confirmed: Boolean? = null) {
        client.respondExtensionUi(req.id()) { o ->
            if (value != null) o.addProperty("value", value)
            if (confirmed != null) o.addProperty("confirmed", confirmed)
        }
        publishWebState()
    }
}
