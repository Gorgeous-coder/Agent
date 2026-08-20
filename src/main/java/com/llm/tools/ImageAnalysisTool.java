package com.llm.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ImageAnalysisTool {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:Qwen/Qwen3.5-35B-A3B}")
    private String visionModel;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "分析和识别图片内容。当用户发送了图片，或者要求识别、解析、查看、描述图片时调用此工具。")
    public String analyzeImage(
            @ToolParam(description = "用户对图片的具体要求或提问，如'描述这张图片'、'图中写了什么'") String prompt,
            @ToolParam(description = "图片的二进制字节数据（byte[]）") byte[] imageBytes) {
        long start = System.currentTimeMillis();
        log.info("[ImageAnalysisTool] 被调用: prompt={}, imageSize={}KB, model={}",
                prompt, imageBytes != null ? imageBytes.length / 1024 : 0, visionModel);

        try {
            if (imageBytes == null || imageBytes.length == 0) {
                return "❌ 图片解析失败：未检测到有效的图片数据。";
            }

            // 1. 转为 Base64 Data URI
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:image/jpeg;base64," + base64Image;

            // 2. 组装多模态请求结构
            Map<String, Object> imageUrlMap = new HashMap<>();
            imageUrlMap.put("url", dataUri);

            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageUrlMap);

            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt != null && !prompt.isEmpty() ? prompt : "请帮我详细识别和解答这张图片的内容");

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", List.of(imageContent, textContent));

            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", visionModel);
            requestBodyMap.put("messages", List.of(message));
            requestBodyMap.put("stream", false);

            String jsonBody = objectMapper.writeValueAsString(requestBodyMap);
            String targetUrl = baseUrl.replaceAll("/+$", "") + "/chat/completions";

            // 3. 使用 OkHttp 直连
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
                    log.error("[ImageAnalysisTool] 识别失败: elapsed={}ms, code={}, err={}", elapsed, response.code(), errBody);
                    return "❌ 视觉服务调用失败，状态码: " + response.code() + ", 错误: " + errBody;
                }

                String respStr = response.body().string();
                JsonNode rootNode = objectMapper.readTree(respStr);
                String content = rootNode.path("choices")
                        .get(0)
                        .path("message")
                        .path("content")
                        .asText();

                long elapsed = System.currentTimeMillis() - start;
                log.info("[ImageAnalysisTool] 识别成功: elapsed={}ms", elapsed);
                return content;
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[ImageAnalysisTool] 识别异常: elapsed={}ms, error={}", elapsed, e.getMessage(), e);
            return "❌ 图片解析异常: " + e.getMessage();
        }
    }
}