package com.llm.service.impl;

import com.llm.service.ConversationHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.llm.service.LlmService;
import com.llm.tools.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@SuppressWarnings("unused")
public class LlmServiceImpl implements LlmService {

    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VoiceTools voiceTools;
    private final LocationTools locationTools;
    private final ImageAnalysisTool imageAnalysisTool;
    private final TranslatorTools translatorTools;
    private final ConversationHistoryService conversationHistoryService;

    public LlmServiceImpl(WeatherTools weatherTools,
                          ImageTools imageTools,
                          VoiceTools voiceTools,
                          LocationTools locationTools,
                          ImageAnalysisTool imageAnalysisTool,
                          TranslatorTools translatorTools,
                          ConversationHistoryService conversationHistoryService) {
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.voiceTools = voiceTools;
        this.locationTools = locationTools;
        this.imageAnalysisTool = imageAnalysisTool;
        this.translatorTools = translatorTools;
        this.conversationHistoryService = conversationHistoryService;
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId) {
        return chat(text, imageUrls, client, userId, null);
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext) {
        return chat(text, imageUrls, client, userId, systemContext, true);
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId,
                       String systemContext, boolean skillEnabled) {
        long start = System.currentTimeMillis();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        String finalText = text;
        if ((finalText == null || finalText.isBlank()) && hasImages) {
            finalText = "请描述这些图片";
        }

        log.info("[LLM-Core] 开始处理: userId={}, text={}", userId, finalText);

        try {
            // ✅ 1. 获取对话历史
            List<Map<String, String>> history = conversationHistoryService.getHistory(userId);
            log.info("[LLM-Core] 历史: {} 条", history.size());

            // ✅ 2. 构建完整 Prompt（含历史）
            String fullPrompt = buildPromptWithHistory(finalText, history);

            // ✅ 3. 构建 Prompt
            var promptBuilder = client.prompt();

            // ✅ 4. 用户消息（含历史上下文）
            promptBuilder.user(fullPrompt);

            // ✅ 5. 图片消息（如果有）
            if (hasImages && !imageUrls.isEmpty()) {
                for (String url : imageUrls) {
                    promptBuilder.user(userSpec -> {
                        userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(url)));
                    });
                }
            }

            // ✅ 6. 注册工具
            if (skillEnabled) {
                promptBuilder.tools(weatherTools, imageTools, voiceTools, locationTools, imageAnalysisTool, translatorTools);
            }

            // ✅ 7. 调用
            var chatResponse = promptBuilder
                    .call()
                    .chatResponse();

            if (chatResponse == null || chatResponse.getResult() == null ||
                    chatResponse.getResult().getOutput().getText() == null) {
                throw new RuntimeException("大模型调用失败：模型未返回有效回复");
            }

            String reply = chatResponse.getResult().getOutput().getText();

            // ✅ 8. 保存对话历史
            conversationHistoryService.saveMessage(userId, "user", finalText, "text");
            conversationHistoryService.saveMessage(userId, "assistant", reply, "text");

            log.info("[LLM-Core] 处理完成: elapsed={}ms, reply={}",
                    System.currentTimeMillis() - start,
                    reply.length() > 50 ? reply.substring(0, 50) + "..." : reply);
            return reply;

        } catch (Exception e) {
            log.error("[LLM-Core] 调用异常: {}", e.getMessage(), e);
            throw (RuntimeException) e;
        }
    }

    /**
     * 构建带历史的 Prompt
     */
    private String buildPromptWithHistory(String currentText, List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();

        if (!history.isEmpty()) {
            sb.append("以下是之前的对话历史：\n");
            for (Map<String, String> msg : history) {
                String role = "user".equals(msg.get("role")) ? "用户" : "助手";
                sb.append(role).append("：").append(msg.get("content")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("用户最新提问：").append(currentText);
        return sb.toString();
    }

    @Override
    public boolean exitSkill(String userId) {
        return true;
    }
}