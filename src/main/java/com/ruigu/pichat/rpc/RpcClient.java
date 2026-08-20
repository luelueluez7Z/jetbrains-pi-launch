package com.ruigu.pichat.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * pi RPC 子进程客户端。
 * <p>
 * spawn `node <cli.js> --mode rpc`，通过 JSONL（LF 分隔）在 stdin/stdout 上通信。
 * 读线程解析事件并分发到 {@link PiListener}；命令发送线程安全，响应通过 id 关联。
 */
public class RpcClient {

    private static final Gson GSON = new Gson();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final Path cwd;
    private final CopyOnWriteArrayList<PiListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<RpcResponse>> pending = new ConcurrentHashMap<>();

    private Process process;
    private PrintWriter writer;
    private volatile boolean running;
    private volatile boolean closing;
    private StringBuilder stderrTail;
    private Consumer<JsonObject> stateReadyConsumer;

    public RpcClient(Path cwd) {
        this.cwd = cwd;
    }

    // ================= 监听 =================

    public void addListener(PiListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PiListener listener) {
        listeners.remove(listener);
    }

    /** 进程就绪（首次 get_state 成功）后回调。 */
    public void onStateReady(Consumer<JsonObject> consumer) {
        this.stateReadyConsumer = consumer;
    }

    // ================= 生命周期 =================

    /** 启动 pi RPC 子进程。抛异常表示无法启动。 */
    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) return;

        String node = PiLocator.resolveNode();
        String cli = PiLocator.findCliJs();
        if (node == null || cli == null) {
            throw new IOException("无法定位 node 或 pi：\n" + PiLocator.describe());
        }

        List<String> cmd = List.of(node, cli, "--mode", "rpc");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("启动 pi 进程失败: " + e.getMessage() + "\n" + PiLocator.describe(), e);
        }

        writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        running = true;
        closing = false;

        Thread stderrThread = new Thread(this::readStderrLoop, "pi-rpc-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        Thread readerThread = new Thread(this::readLoop, "pi-rpc-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 就绪探测
        getState().thenAccept(res -> {
            if (res != null && res.success() && res.data() != null) {
                Consumer<JsonObject> c = stateReadyConsumer;
                if (c != null) c.accept(res.data());
            }
        });
    }

    /** 主动关闭子进程。 */
    public synchronized void close() {
        if (closing) return;
        closing = true;
        running = false;
        Process p = process;
        if (p != null && p.isAlive()) {
            try { p.destroy(); } catch (Exception ignored) {}
            try { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (Exception ignored) {}
        }
        if (writer != null) {
            try { writer.close(); } catch (Exception ignored) {}
        }
        failAllPending();
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public Path getCwd() {
        return cwd;
    }

    // ================= 读循环 =================

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    dispatch(event);
                } catch (Exception ignored) {
                    // 跳过无法解析的行
                }
            }
        } catch (Exception e) {
            if (!closing) fire(l -> l.onError("读取 pi 输出失败: " + e.getMessage()));
        } finally {
            if (!closing) {
                running = false;
                failAllPending();
                fire(l -> l.onProcessExit(safeExitValue(), stderrTail == null ? "" : stderrTail.toString()));
            }
        }
    }

    private int safeExitValue() {
        try {
            return process != null && !process.isAlive() ? process.exitValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void readStderrLoop() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (sb.length() > 8192) sb.delete(0, 4096);
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {}
        this.stderrTail = sb;
    }

    // ================= 事件分发 =================

    private void dispatch(JsonObject e) {
        String type = e.has("type") ? e.get("type").getAsString() : "";
        switch (type) {
            case "response" -> {
                RpcResponse res = RpcResponse.from(e);
                if (res.id() != null) {
                    CompletableFuture<RpcResponse> f = pending.remove(res.id());
                    if (f != null) f.complete(res);
                }
                fire(l -> l.onResponse(res));
            }
            case "agent_start" -> fire(PiListener::onAgentStart);
            case "agent_end" -> fire(l -> {
                JsonArray messages = e.has("messages") && e.get("messages").isJsonArray() ? e.getAsJsonArray("messages") : null;
                boolean willRetry = e.has("willRetry") && e.get("willRetry").getAsBoolean();
                l.onAgentEnd(messages, willRetry);
            });
            case "agent_settled" -> fire(PiListener::onAgentSettled);
            case "turn_start" -> fire(PiListener::onTurnStart);
            case "turn_end" -> fire(l -> l.onTurnEnd(e));
            case "message_start" -> fire(l -> l.onMessageStart(e));
            case "message_update" -> fire(l -> l.onMessageUpdate(e));
            case "message_end" -> fire(l -> l.onMessageEnd(e));
            case "tool_execution_start" -> fire(l -> l.onToolStart(
                    str(e, "toolCallId"), str(e, "toolName"),
                    e.has("args") && e.get("args").isJsonObject() ? e.getAsJsonObject("args") : null));
            case "tool_execution_update" -> fire(l -> l.onToolUpdate(
                    str(e, "toolCallId"), str(e, "toolName"),
                    e.has("partialResult") && e.get("partialResult").isJsonObject() ? e.getAsJsonObject("partialResult") : null));
            case "tool_execution_end" -> fire(l -> l.onToolEnd(
                    str(e, "toolCallId"), str(e, "toolName"),
                    e.has("isError") && e.get("isError").getAsBoolean(),
                    e.has("result") && e.get("result").isJsonObject() ? e.getAsJsonObject("result") : null));
            case "bash_execution_update" -> fire(l -> l.onBashExecutionUpdate(str(e, "id"), str(e, "delta")));
            case "queue_update" -> fire(l -> l.onQueueUpdate(e));
            case "compaction_start" -> fire(l -> l.onCompactionStart(str(e, "reason")));
            case "compaction_end" -> fire(l -> l.onCompactionEnd(e.has("result") && e.get("result").isJsonObject() ? e.getAsJsonObject("result") : null));
            case "auto_retry_start" -> fire(l -> l.onAutoRetryStart(
                    intOr(e, "attempt"), intOr(e, "maxAttempts"), str(e, "errorMessage")));
            case "auto_retry_end" -> fire(l -> l.onAutoRetryEnd(
                    e.has("success") && e.get("success").getAsBoolean(), intOr(e, "attempt"), str(e, "finalError")));
            case "extension_ui_request" -> fire(l -> l.onExtensionUi(new ExtensionUiRequest(str(e, "id"), str(e, "method"), e)));
            case "extension_error" -> fire(l -> l.onExtensionError(str(e, "extensionPath"), str(e, "error")));
            default -> fire(l -> l.onEvent(e));
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static int intOr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsInt() : 0;
    }

    private void fire(Consumer<PiListener> fn) {
        for (PiListener l : listeners) {
            try {
                fn.accept(l);
            } catch (Exception ignored) {
                // 单个监听器异常不影响分发
            }
        }
    }

    // ================= 命令发送 =================

    /** 发送命令，通过 id 关联响应，返回 future。 */
    public CompletableFuture<RpcResponse> send(Consumer<JsonObject> build) {
        JsonObject cmd = new JsonObject();
        build.accept(cmd);
        String id = "req-" + SEQ.incrementAndGet();
        cmd.addProperty("id", id);
        CompletableFuture<RpcResponse> f = new CompletableFuture<>();
        // Register before writing. pi can answer synchronously; registering after
        // write allows the reader thread to consume the response before the
        // future is visible in pending, leaving callers waiting forever.
        pending.put(id, f);
        if (!write(cmd)) {
            pending.remove(id, f);
            f.completeExceptionally(new IOException("pi 进程未运行"));
            return f;
        }
        return f;
    }

    /** 发送命令并等待响应（带超时）。 */
    public CompletableFuture<RpcResponse> sendTimed(Consumer<JsonObject> build, long timeoutMs) {
        CompletableFuture<RpcResponse> f = send(build);
        f.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        return f;
    }

    /** 一次性命令（不关心响应）。 */
    public void sendFireAndForget(Consumer<JsonObject> build) {
        try {
            write(buildCommand(build));
        } catch (Exception ignored) {}
    }

    private JsonObject buildCommand(Consumer<JsonObject> build) {
        JsonObject cmd = new JsonObject();
        build.accept(cmd);
        return cmd;
    }

    private synchronized boolean write(JsonObject cmd) {
        if (writer == null) return false;
        try {
            writer.println(GSON.toJson(cmd));
            writer.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================= 常用命令 =================

    public CompletableFuture<RpcResponse> prompt(String message) {
        return send(cmd -> {
            cmd.addProperty("type", "prompt");
            cmd.addProperty("message", message);
        });
    }

    public CompletableFuture<RpcResponse> promptSteer(String message) {
        return send(cmd -> {
            cmd.addProperty("type", "prompt");
            cmd.addProperty("message", message);
            cmd.addProperty("streamingBehavior", "steer");
        });
    }

    public CompletableFuture<RpcResponse> steer(String message) {
        return send(cmd -> {
            cmd.addProperty("type", "steer");
            cmd.addProperty("message", message);
        });
    }

    public CompletableFuture<RpcResponse> followUp(String message) {
        return send(cmd -> {
            cmd.addProperty("type", "follow_up");
            cmd.addProperty("message", message);
        });
    }

    public CompletableFuture<RpcResponse> abort() {
        return send(cmd -> cmd.addProperty("type", "abort"));
    }

    public CompletableFuture<RpcResponse> getState() {
        return send(cmd -> cmd.addProperty("type", "get_state"));
    }

    public CompletableFuture<RpcResponse> getMessages() {
        return send(cmd -> cmd.addProperty("type", "get_messages"));
    }

    public CompletableFuture<RpcResponse> getAvailableModels() {
        return send(cmd -> cmd.addProperty("type", "get_available_models"));
    }

    public CompletableFuture<RpcResponse> setModel(String provider, String modelId) {
        return send(cmd -> {
            cmd.addProperty("type", "set_model");
            cmd.addProperty("provider", provider);
            cmd.addProperty("modelId", modelId);
        });
    }

    public CompletableFuture<RpcResponse> cycleModel() {
        return send(cmd -> cmd.addProperty("type", "cycle_model"));
    }

    public CompletableFuture<RpcResponse> setThinkingLevel(String level) {
        return send(cmd -> {
            cmd.addProperty("type", "set_thinking_level");
            cmd.addProperty("level", level);
        });
    }

    public CompletableFuture<RpcResponse> getThinkingLevels() {
        return send(cmd -> cmd.addProperty("type", "get_available_thinking_levels"));
    }

    public CompletableFuture<RpcResponse> newSession() {
        return send(cmd -> cmd.addProperty("type", "new_session"));
    }

    public CompletableFuture<RpcResponse> switchSession(String sessionPath) {
        return send(cmd -> {
            cmd.addProperty("type", "switch_session");
            cmd.addProperty("sessionPath", sessionPath);
        });
    }

    public CompletableFuture<RpcResponse> getEntries(String since) {
        return send(cmd -> {
            cmd.addProperty("type", "get_entries");
            if (since != null) cmd.addProperty("since", since);
        });
    }

    public CompletableFuture<RpcResponse> getCommands() {
        return send(cmd -> cmd.addProperty("type", "get_commands"));
    }

    public CompletableFuture<RpcResponse> getSessionStats() {
        return send(cmd -> cmd.addProperty("type", "get_session_stats"));
    }

    public CompletableFuture<RpcResponse> getLastAssistantText() {
        return send(cmd -> cmd.addProperty("type", "get_last_assistant_text"));
    }

    public void respondExtensionUi(String id, Consumer<JsonObject> valueBuild) {
        JsonObject res = new JsonObject();
        res.addProperty("type", "extension_ui_response");
        res.addProperty("id", id);
        valueBuild.accept(res);
        write(res);
    }

    private void failAllPending() {
        for (CompletableFuture<RpcResponse> f : pending.values()) {
            f.completeExceptionally(new IOException("pi 进程已退出"));
        }
        pending.clear();
    }
}
