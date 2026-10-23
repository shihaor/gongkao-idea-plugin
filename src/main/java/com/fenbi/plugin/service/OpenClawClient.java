package com.fenbi.plugin.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * OpenClaw Gateway 客户端：
 * - /v1/chat/completions 同步问询（生成计划、分析结果）
 * - /hooks/wake 单向推送（让 agent 把反馈发到 IM）
 */
public class OpenClawClient {
    private final Supplier<String> baseUrl;
    private final Supplier<String> gatewayToken;
    private final Supplier<String> hookUrl;
    private final Supplier<String> hookToken;
    private final Supplier<String> model;
    private final HttpClient http;

    public OpenClawClient(Supplier<String> baseUrl, Supplier<String> gatewayToken,
                          Supplier<String> hookUrl, Supplier<String> hookToken, Supplier<String> model) {
        this.baseUrl = baseUrl;
        this.gatewayToken = gatewayToken;
        this.hookUrl = hookUrl;
        this.hookToken = hookToken;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isChatConfigured() {
        return !base().isBlank() && !safe(gatewayToken).isBlank();
    }

    public String chatEndpoint() {
        return base() + "/v1/chat/completions";
    }

    public boolean isHookConfigured() {
        return !safe(hookUrl).isBlank() && !safe(hookToken).isBlank();
    }

    private static String safe(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String base() {
        String value = safe(baseUrl).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/v1/chat/completions")) {
            value = value.substring(0, value.length() - "/v1/chat/completions".length());
        } else if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - 3);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public record ChatResult(String content, long elapsedMs, int promptTokens, int completionTokens) {
    }

    /**
     * 同步问 agent，返回文本回复和耗时/token 信息。
     */
    public ChatResult chat(String system, String user) throws IOException {
        if (!isChatConfigured()) {
            throw new IOException("未配置接口地址或 API Key");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", safe(model).isBlank() ? "openclaw/main" : safe(model));
        body.addProperty("user", "conv:fenbi-idea-plugin");
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);

        long start = System.nanoTime();
        String response = post(base() + "/v1/chat/completions", body.toString(), safe(gatewayToken), 120);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return parseChat(response, elapsedMs);
    }

    private static ChatResult parseChat(String response, long elapsedMs) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("解析接口回复失败：" + abbreviate(response), e);
        }
        String content = extractContent(root);
        int promptTokens = 0;
        int completionTokens = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            promptTokens = intValue(usage, "prompt_tokens");
            completionTokens = intValue(usage, "completion_tokens");
        }
        return new ChatResult(content, elapsedMs, promptTokens, completionTokens);
    }

    private static int intValue(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 单向推送，让 OpenClaw 把内容发到绑定的 IM。
     */
    public void wake(String text) throws IOException {
        if (!isHookConfigured()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        post(safe(hookUrl), body.toString(), safe(hookToken), 30);
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String post(String url, String body, String token, int timeoutSeconds) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ShuaBangBang/0.4")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("接口鉴权失败（" + response.statusCode() + "），检查 Token");
        }
        if (response.statusCode() >= 400) {
            throw new IOException("接口返回 " + response.statusCode() + "：" + abbreviate(response.body()));
        }
        return response.body();
    }

    private static String extractContent(JsonObject root) throws IOException {
        try {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("接口回复里没有 choices");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            JsonElement content = message.get("content");
            if (content == null || content.isJsonNull()) {
                throw new IOException("接口回复内容为空");
            }
            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }
            if (content.isJsonArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                        builder.append(part.getAsJsonObject().get("text").getAsString());
                    } else if (part.isJsonPrimitive()) {
                        builder.append(part.getAsString());
                    }
                }
                return builder.toString();
            }
            return content.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析接口回复失败：" + abbreviate(root.toString()), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
