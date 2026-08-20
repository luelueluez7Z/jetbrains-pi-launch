package com.ruigu.pichat.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * RPC 命令的统一响应。success=false 时 error 非空。
 */
public record RpcResponse(String id, String command, boolean success, JsonObject data, String error) {

    public static RpcResponse from(JsonObject json) {
        String id = json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString() : null;
        String command = json.has("command") && !json.get("command").isJsonNull() ? json.get("command").getAsString() : null;
        boolean success = json.has("success") && json.get("success").getAsBoolean();
        JsonObject data = json.has("data") && json.get("data").isJsonObject() ? json.get("data").getAsJsonObject() : null;
        String error = json.has("error") && !json.get("error").isJsonNull() ? json.get("error").getAsString() : null;
        return new RpcResponse(id, command, success, data, error);
    }

    public String dataString(String key) {
        if (data == null || !data.has(key) || data.get(key).isJsonNull()) return null;
        JsonElement el = data.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }
}
