package com.ruigu.pichat.rpc;

import com.google.gson.JsonObject;

/**
 * RPC 事件监听接口。所有回调在 RPC 读取线程上触发，UI 层需自行切换到 EDT。
 */
public interface PiListener {

    /** 所有事件的统一入口（原始 JSON），通常不需要实现。 */
    default void onEvent(JsonObject raw) {}

    default void onResponse(RpcResponse res) {}

    // ---- agent 生命周期 ----
    default void onAgentStart() {}
    default void onAgentEnd(com.google.gson.JsonArray messages, boolean willRetry) {}
    default void onAgentSettled() {}

    // ---- turn 生命周期 ----
    default void onTurnStart() {}
    default void onTurnEnd(JsonObject message) {}

    // ---- 消息 ----
    default void onMessageStart(JsonObject message) {}
    default void onMessageUpdate(JsonObject update) {}
    default void onMessageEnd(JsonObject message) {}

    // ---- 工具执行 ----
    default void onToolStart(String toolCallId, String toolName, JsonObject args) {}
    default void onToolUpdate(String toolCallId, String toolName, JsonObject partialResult) {}
    default void onToolEnd(String toolCallId, String toolName, boolean isError, JsonObject result) {}
    default void onBashExecutionUpdate(String id, String delta) {}

    // ---- 队列 / 压缩 / 重试 ----
    default void onQueueUpdate(JsonObject queue) {}
    default void onCompactionStart(String reason) {}
    default void onCompactionEnd(JsonObject result) {}
    default void onAutoRetryStart(int attempt, int maxAttempts, String errorMessage) {}
    default void onAutoRetryEnd(boolean success, int attempt, String finalError) {}

    // ---- 扩展 UI ----
    default void onExtensionUi(ExtensionUiRequest req) {}
    default void onExtensionError(String extensionPath, String error) {}

    // ---- 进程 ----
    default void onProcessExit(int exitCode, String stderrTail) {}
    default void onError(String message) {}
}
