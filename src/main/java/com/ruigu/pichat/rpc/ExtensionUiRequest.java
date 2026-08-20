package com.ruigu.pichat.rpc;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 扩展 UI 请求（extension_ui_request）：select/confirm/input/editor 为对话框方法，
 * notify/setStatus/setWidget/setTitle/set_editor_text 为一次性通知。
 */
public record ExtensionUiRequest(String id, String method, JsonObject raw) {

    public String title() {
        return raw.has("title") && !raw.get("title").isJsonNull() ? raw.get("title").getAsString() : "";
    }

    public String message() {
        return raw.has("message") && !raw.get("message").isJsonNull() ? raw.get("message").getAsString() : "";
    }

    public String placeholder() {
        return raw.has("placeholder") && !raw.get("placeholder").isJsonNull() ? raw.get("placeholder").getAsString() : "";
    }

    public String prefill() {
        return raw.has("prefill") && !raw.get("prefill").isJsonNull() ? raw.get("prefill").getAsString() : "";
    }

    public long timeoutMs() {
        return raw.has("timeout") && !raw.get("timeout").isJsonNull() ? raw.get("timeout").getAsLong() : -1;
    }

    public List<String> options() {
        if (!raw.has("options") || !raw.get("options").isJsonArray()) return List.of();
        return raw.getAsJsonArray("options").asList().stream()
                .map(e -> e.getAsString())
                .toList();
    }

    public boolean dialog() {
        return switch (method) {
            case "select", "confirm", "input", "editor" -> true;
            default -> false;
        };
    }
}
