package com.llm.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@SuppressWarnings("unused")
public class ImageTools {

    // ✅ 加上默认值，如果配置没有就用这个
    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${spring.ai.openai.image.model:Tongyi-MAI/Z-Image-Turbo}")
    private String imageModel;

    /**
     * 在内存中暂存最新的原生图片 URL
     */
    public static String lastGeneratedImageUrl;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "生成图片并返回。当用户要求画图、生成图片、绘制、创作图像时调用此工具。")
    public String generateImage(
            @ToolParam(description = "图片描述或编辑指令，如'一只猫在太空'") String prompt,
            @ToolParam(description = "参考图片URL，无参考图时传null", required = false) String imageUrl) {
        long start = System.currentTimeMillis();
        log.info("[ImageTools] 被调用: prompt={}, hasRefImage={}, model={}", prompt, imageUrl != null, imageModel);

        // ✅ 如果 API Key 为空，提前返回
        if (apiKey == null || apiKey.isEmpty()) {
            return "❌ 请先配置 SILICONFLOW_API_KEY 环境变量";
        }

        try {
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", imageModel);
            requestBodyMap.put("prompt", prompt != null && !prompt.isEmpty() ? prompt : "请生成一张精美的图片");
            requestBodyMap.put("n", 1);
            requestBodyMap.put("size", "1024x1024");

            if (imageUrl != null && !imageUrl.isEmpty()) {
                requestBodyMap.put("image", imageUrl);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBodyMap);
            String targetUrl = baseUrl.replaceAll("/+$", "") + "/images/generations";

            log.info("[ImageTools] 请求URL: {}", targetUrl);

            Request request = new Request.Builder()
                    .url(targetUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "unknown";
                    long elapsed = System.currentTimeMillis() - start;
                    log.error("[ImageTools] 生成失败: elapsed={}ms, code={}, err={}", elapsed, response.code(), errBody);
                    return "❌ 图片生成失败 (状态码: " + response.code() + ")";
                }

                String respStr = response.body().string();
                JsonNode rootNode = objectMapper.readTree(respStr);
                JsonNode dataNode = rootNode.path("data");

                if (dataNode.isArray() && !dataNode.isEmpty()) {
                    String url = dataNode.get(0).path("url").asText();
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[ImageTools] 生成成功: elapsed={}ms, url={}", elapsed, url);

                    lastGeneratedImageUrl = url;
                    return "图片已成功生成。";
                }
                long elapsed = System.currentTimeMillis() - start;
                log.error("[ImageTools] 解析失败: elapsed={}ms, response={}", elapsed, respStr);
                return "❌ 图片生成解析失败：未找到返回的 URL";
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[ImageTools] 生成异常: elapsed={}ms, prompt={}, error={}", elapsed, prompt, e.getMessage(), e);
            return "❌ 图片生成异常：" + e.getMessage();
        }
    }
}