package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容的 AI 对话客户端。
 *
 * <p>适用于 OpenAI / DeepSeek / 通义千问 / Moonshot / 智谱GLM 等使用
 * {@code /v1/chat/completions} 标准接口的大模型服务，只需配置不同的
 * {@code base-url} / {@code api-key} / {@code model} 即可切换。
 */
public class AiClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String chatCompletionsUrl;

    public AiClient(String baseUrl, String apiKey, String model,
                    double temperature, int maxTokens) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.chatCompletionsUrl = buildChatUrl(baseUrl);
    }

    /**
     * 智能拼接 chat completions 地址：base-url 填
     * "https://api.deepseek.com" 或 ".../v1" 都能正确处理。
     */
    private static String buildChatUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("ai.base-url 不能为空");
        }
        String b = baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/v1")) {
            return b + "/chat/completions";
        }
        return b + "/v1/chat/completions";
    }

    /**
     * 多模态对话：把最后一条 user 消息附加一张图片（base64 data URL），
     * 适用于 qwen-vl-max 等视觉模型"看图"。
     *
     * @param textMessages 普通文本对话消息（与 {@link #chat} 一致）
     * @param imageBytes   图片二进制内容
     * @param mimeType     图片 MIME 类型（如 image/jpeg、image/png）
     * @return AI 回复的文本内容
     */
    public String chatWithImage(
            List<Map<String, String>> textMessages, byte[] imageBytes, String mimeType)
            throws IOException {
        ObjectNode body = buildBody();
        ArrayNode msgs = body.putArray("messages");
        for (int i = 0; i < textMessages.size(); i++) {
            Map<String, String> m = textMessages.get(i);
            ObjectNode msg = msgs.addObject();
            msg.put("role", m.get("role"));
            boolean isLastUser =
                    (i == textMessages.size() - 1)
                            && "user".equals(m.get("role"))
                            && imageBytes != null
                            && imageBytes.length > 0;
            if (isLastUser) {
                // content 为数组：文本 + 图片
                ArrayNode content = msg.putArray("content");
                ObjectNode textPart = content.addObject();
                textPart.put("type", "text");
                textPart.put("text", m.get("content"));
                ObjectNode imgPart = content.addObject();
                imgPart.put("type", "image_url");
                ObjectNode imgUrl = imgPart.putObject("image_url");
                String mime = (mimeType == null || mimeType.isEmpty()) ? "image/jpeg" : mimeType;
                imgUrl.put("url",
                        "data:" + mime + ";base64,"
                                + Base64.getEncoder().encodeToString(imageBytes));
            } else {
                msg.put("content", m.get("content"));
            }
        }
        return doPost(body);
    }

    /** 发起一次对话补全请求。 */
    public String chat(List<Map<String, String>> messages) throws IOException {
        ObjectNode body = buildBody();
        ArrayNode msgs = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode msg = msgs.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        }
        return doPost(body);
    }

    private ObjectNode buildBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    private String doPost(ObjectNode body) throws IOException {
        RequestBody requestBody = RequestBody.create(MAPPER.writeValueAsString(body), JSON);
        Request request = new Request.Builder()
                .url(chatCompletionsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("AI 请求失败: HTTP " + response.code()
                        + " - " + truncate(respBody, 500));
            }
            JsonNode root = MAPPER.readTree(respBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isEmpty()) {
                throw new IOException("AI 响应缺少 content 字段: " + truncate(respBody, 500));
            }
            return content.asText();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
