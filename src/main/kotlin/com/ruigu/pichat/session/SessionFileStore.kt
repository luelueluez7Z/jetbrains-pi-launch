package com.ruigu.pichat.session

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ruigu.pichat.ui.ChatMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator

/**
 * 会话文件存取层（纯函数，无 IntelliJ / 无 UI 依赖）。
 * <p>
 * 从 ChatPanel 抽出的所有「读/写/解析 pi 会话 jsonl」逻辑集中于此，便于独立测试与复用。
 * 调用方（ChatPanel）负责线程调度（磁盘 IO 必须在后台线程）与状态管理。
 */

/** 会话列表条目（对应前端历史列表的一行）。 */
data class SessionItem(
    val path: String,
    val name: String,
    val isCurrent: Boolean,
    val title: String? = null,
    val id: String = parseSessionId(name),
    val firstMessage: String = "",
    val messageCount: Int = 0,
    val lastTimestamp: Long = 0L,
)

/**
 * 预编译正则：热点路径（每个 text_delta、每条历史消息、每行会话 jsonl）此前每次调用
 * 都重新编译 Regex，长会话下是明显 CPU 热点，统一提取为常量复用。
 */
object SessionRegexes {
    val SESSION_ID_IN_NAME = Regex("_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.jsonl$")

    // ---- stripMagicContextMarks（§ = U+00A7，° = U+00B0）----
    /** 1) 开头规范前缀：一个或多个 §N§ + 尾随空格 */
    val LEADING_MARK_PREFIX = Regex("^(\\u00A7\\d+\\u00A7\\s*)+")
    /** 2) 全局完整对 §N§ */
    val COMPLETE_MARK = Regex("\\u00A7\\d+\\u00A7")
    /** 3) malformed hybrid：§N"§ / §N"§§N§ / §N" */
    val MALFORMED_MARK = Regex("\\u00A7\\d+\\\">(?:\\u00A7(?:\\d+\\u00A7)?)?")
    /** 4) dangling：§N + 单个 improvised closer（不碰 §5.1 小数引用） */
    val DANGLING_MARK = Regex("\\u00A7\\d+(?!\\.\\d)[^\\s\\u00A7\\w.]?")
    /** 5) 残余 °° 闭合符（magic-context 只吃一个 °） */
    val DEGREE_RUN = Regex("\\u00B0{2,}")
    /** 5b) 开头孤立 ° */
    val LEADING_DEGREES = Regex("^\\s*\\u00B0+")
    /** 6) stray § */
    val STRAY_SECTION = Regex("\\u00A7")

    // ---- 会话 jsonl 解析 ----
    val MESSAGE_TIMESTAMP = Regex("\"timestamp\"\\s*:\\s*\"([^\"]+)\"")
    val SESSION_INFO_NAME = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    val WHITESPACE_RUN = Regex("[\\r\\n\\t]+")
    val NEWLINE_RUN = Regex("[\\r\\n]+")
}

/** 从会话文件名解析 pi 的 UUID id（形如 2026-08-21T19-24-10-173Z_01a025c7-...jsonl -> 01a025c7-...）。 */
fun parseSessionId(name: String): String {
    val m = SessionRegexes.SESSION_ID_IN_NAME.find(name)
    return m?.groupValues?.get(1) ?: name.removeSuffix(".jsonl")
}

/** JsonObject 取字符串字段（缺失/null 返回空串）。jsonl 解析各处共用。 */
internal fun JsonObject.str(key: String): String {
    return if (has(key) && !get(key).isJsonNull) get(key).asString else ""
}

/** 把 pi 消息 content（字符串或 [{type:text,text:...}] 数组）拼成纯文本。 */
fun textOf(content: JsonElement?): String {
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
fun isStrayThinkingTag(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isEmpty() ||
        trimmed == "</thinking>" || trimmed == "<thinking>" ||
        trimmed == "</reasoning>" || trimmed == "<reasoning>" ||
        trimmed == "</thought>" || trimmed == "<thought>" ||
        trimmed == " response" || trimmed == " thinking" ||
        trimmed == "response"
}

/** 剥离 thinking 内容里残留的 thinking 标签（open/close，含 </think> 等变体）。 */
fun stripThinkingTags(text: String): String {
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
fun stripMagicContextMarks(text: String): String {
    var out = text
    // 1) 开头规范前缀：一个或多个 §N§ + 尾随空格
    out = out.replace(SessionRegexes.LEADING_MARK_PREFIX, "")
    // 2) 全局完整对 §N§
    out = out.replace(SessionRegexes.COMPLETE_MARK, "")
    // 3) malformed hybrid：§N">§ / §N">§N§ / §N">
    out = out.replace(SessionRegexes.MALFORMED_MARK, "")
    // 4) dangling：§N + 单个 improvised closer（非 word/空格/§/句点；不碰 §5.1 小数引用）
    out = out.replace(SessionRegexes.DANGLING_MARK, "")
    // 5) 补充：清残余的 °°（magic-context 只吃一个 °，模型 improvised 的 °° 闭合符会残留）
    out = out.replace(SessionRegexes.DEGREE_RUN, "")
    out = out.replace(SessionRegexes.LEADING_DEGREES, "")
    // 6) stray §
    out = out.replace(SessionRegexes.STRAY_SECTION, "")
    return out.trim()
}

/** 把一条会话消息（pi AgentMessage 或其 jsonl 形态）转换为 ChatMessage。 */
fun applySessionMessage(messages: MutableList<ChatMessage>, toolMap: HashMap<String, ChatMessage>, m: JsonObject) {
    if (!m.has("role")) return
    val role = m.get("role")?.takeIf { it.isJsonPrimitive }?.asString ?: return
    when (role) {
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
                            val text = block.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            if (text != null) {
                                // magic-context 压缩重建会把 <thinking>/</thinking> 标签错位：
                                // open 标签残留进 thinking 内容，close 标签（如 `</thinking>`）残留进 text 块
                                // （可能是孤立块，也可能与真实文本混合）。统一剥离标签，剥离后为空的块忽略。
                                // 同时剥离 magic-context 注入的 §N§ / §N°° 消息标记。
                                val cleaned = stripMagicContextMarks(stripThinkingTags(text))
                                if (!cleaned.isBlank() && !isStrayThinkingTag(cleaned)) am.appendText(cleaned)
                            }
                        }
                        "thinking" -> {
                            val thinking = block.get("thinking")?.takeIf { it.isJsonPrimitive }?.asString
                            if (thinking != null) {
                                // 剥离 thinking 内容里残留的 thinking 标签与 magic-context 标记
                                am.appendThinking(stripMagicContextMarks(stripThinkingTags(thinking)))
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

/** 直接读会话 jsonl 文件解析历史消息（与终端 pi 共享，能看到外部写入的最新内容）。 */
fun readSessionFile(file: String): List<ChatMessage>? {
    return try {
        val parsed = mutableListOf<ChatMessage>()
        val toolMap = HashMap<String, ChatMessage>()
        Files.readAllLines(Path.of(file)).forEach { line ->
            if (line.isBlank()) return@forEach
            val entry = try {
                JsonParser.parseString(line).asJsonObject
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

/**
 * 历史加载收尾：把未配对 toolResult 的 tool 消息（进程中断/会话被强杀，工具没跑完）
 * 标记为中断（error + 说明），否则前端永远显示“运行中”转圈（没有 toolResult 可渲染）。
 */
fun finalizeInterruptedTools(parsed: List<ChatMessage>) {
    parsed.forEach { m ->
        if (m.kind == ChatMessage.Kind.TOOL && m.toolStatus == "running") {
            m.toolStatus = "error"
            m.toolResult = "（会话中断，工具未执行完成）"
        }
    }
}

/** 读会话文件元信息（对齐 pi TUI）：首条 user 消息摘要、消息数、最后活动时间戳。
 *  返回 Triple(firstMessage, messageCount, lastTimestampEpochMillis)。 */
fun readSessionMeta(file: Path): Triple<String, Int, Long> {
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
                            firstMessage = text.replace(SessionRegexes.WHITESPACE_RUN, " ").trim().take(120)
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
fun extractMessageTimestamp(line: String): Long {
    return try {
        val m = SessionRegexes.MESSAGE_TIMESTAMP.find(line) ?: return 0L
        Instant.parse(m.groupValues[1]).toEpochMilli()
    } catch (e: Exception) {
        0L
    }
}

/** 从 jsonl 行提取首条 user 消息文本（content 为字符串或数组中的首个 text 块）。 */
fun extractFirstUserText(line: String): String {
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

/**
 * 从会话文件尾部反向读取最新的 session_info 记录（pi 的标题存储方式），
 * 与终端 pi 的 renameSession 行为一致。
 */
fun readSessionTitle(path: Path): String? {
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
                val nameMatch = SessionRegexes.SESSION_INFO_NAME.find(line)
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

/** 读取会话文件最后一条记录的 id（作为新 session_info 的 parentId，保持 pi 的会话树结构）。 */
fun readLeafId(target: Path): String {
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
    return leafId
}

/**
 * 向会话文件追加一条 session_info 记录（镜像 pi TUI 的 renameSession，
 * 与终端 pi 的标题存储方式一致，见 SessionManager.appendSessionInfo）。
 */
fun appendSessionInfo(target: Path, name: String) {
    // 读取最后一条记录的 id 作为 parentId，保持 pi 的会话树结构
    val leafId = readLeafId(target)
    val entry = JsonObject().apply {
        addProperty("type", "session_info")
        addProperty("id", java.util.UUID.randomUUID().toString())
        if (leafId.isNotEmpty()) addProperty("parentId", leafId)
        addProperty("timestamp", Instant.now().toString())
        addProperty("name", name)
    }
    Files.writeString(
        target, GsonHolder.GSON.toJson(entry) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.APPEND,
    )
}

/** 扫描会话目录（按最后修改时间倒序），逐文件读取元信息与标题。磁盘 IO，调用方需在后台线程执行。 */
fun scanSessionDirectory(dir: Path, currentName: String): List<SessionItem> {
    val list = mutableListOf<SessionItem>()
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
    return list
}

/** Gson 单例（避免每个调用点 new Gson）。 */
private object GsonHolder {
    val GSON = com.google.gson.Gson()
}
