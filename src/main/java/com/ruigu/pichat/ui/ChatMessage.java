package com.ruigu.pichat.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 会话中的一条消息。可变对象，流式更新时直接修改字段。
 */
public class ChatMessage {

    public enum Kind { USER, ASSISTANT, THINKING, TOOL, SYSTEM, ERROR }

    private Kind kind;
    private String text = "";
    private String thinking = "";
    private String toolName;
    private String toolCallId;
    private String argsSummary = "";
    private String toolStatus = "";
    private String toolResult = "";
    /** tool 执行返回的结构化详情（如 subagent 的 details.results[].messages），随 toolResult 一并传给前端。 */
    private JsonElement toolResultDetails;
    /** argsSummary 的解析缓存（toolUseMessage 每次 publishWebState 都要用，解析一次复用）。 */
    private JsonElement argsJsonCache;
    private boolean argsJsonParsed;
    private long timestamp = System.currentTimeMillis();

    public static ChatMessage user(String text) {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.USER;
        m.text = text;
        return m;
    }

    public static ChatMessage assistant() {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.ASSISTANT;
        return m;
    }

    public static ChatMessage thinking() {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.THINKING;
        return m;
    }

    public static ChatMessage tool(String toolCallId, String toolName, String argsSummary) {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.TOOL;
        m.toolCallId = toolCallId;
        m.toolName = toolName;
        m.argsSummary = argsSummary;
        m.toolStatus = "running";
        return m;
    }

    public static ChatMessage system(String text) {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.SYSTEM;
        m.text = text;
        return m;
    }

    public static ChatMessage error(String text) {
        ChatMessage m = new ChatMessage();
        m.kind = Kind.ERROR;
        m.text = text;
        return m;
    }

    public Kind getKind() { return kind; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public void appendText(String delta) { this.text += delta; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public void appendThinking(String delta) { this.thinking += delta; }
    public String getToolName() { return toolName; }
    public String getToolCallId() { return toolCallId; }
    public String getArgsSummary() { return argsSummary; }
    public void setArgsSummary(String argsSummary) {
        this.argsSummary = argsSummary;
        this.argsJsonParsed = false;
        this.argsJsonCache = null;
    }

    /**
     * argsSummary 的解析结果（带缓存）：合法 JSON 对象返回该对象，否则返回 {summary: ...} 兜底。
     * 缓存在 setArgsSummary 时失效。
     */
    public JsonObject getArgsAsJson() {
        if (!argsJsonParsed) {
            argsJsonParsed = true;
            try {
                JsonElement el = com.google.gson.JsonParser.parseString(argsSummary);
                argsJsonCache = el.isJsonObject()
                        ? el.getAsJsonObject()
                        : fallbackSummary();
            } catch (Exception e) {
                argsJsonCache = fallbackSummary();
            }
        }
        return argsJsonCache instanceof JsonObject ? (JsonObject) argsJsonCache : new JsonObject();
    }

    /** 非法/非对象 argsSummary 的兜底形态。 */
    private JsonObject fallbackSummary() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", argsSummary == null ? "" : argsSummary);
        return o;
    }
    public String getToolStatus() { return toolStatus; }
    public void setToolStatus(String toolStatus) { this.toolStatus = toolStatus; }
    public String getToolResult() { return toolResult; }
    public void setToolResult(String toolResult) { this.toolResult = toolResult; }
    public void appendToolResult(String delta) { this.toolResult += delta; }
    public JsonElement getToolResultDetails() { return toolResultDetails; }
    public void setToolResultDetails(JsonElement toolResultDetails) { this.toolResultDetails = toolResultDetails; }
    public long getTimestamp() { return timestamp; }

    /** 助手消息是否没有任何可见内容。 */
    public boolean isEmpty() {
        return text.isEmpty() && thinking.isEmpty();
    }

    @Override
    public String toString() {
        return kind + ": " + (text.length() > 40 ? text.substring(0, 40) + "…" : text);
    }
}
