package com.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.llm.service.LlmService;
import com.llm.tools.*;

import java.net.URI;
import java.util.List;

@Slf4j
@Service
@SuppressWarnings("unused")
public class LlmServiceImpl implements LlmService {

    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VoiceTools voiceTools;
    private final LocationTools locationTools;
    private final TranslatorTools  translatorTools;
    private final ImageAnalysisTool imageAnalysisTool;

    public LlmServiceImpl(WeatherTools weatherTools,
                          ImageTools imageTools,
                          VoiceTools voiceTools,
                          LocationTools locationTools,
                          TranslatorTools translatorTools,
                          ImageAnalysisTool imageAnalysisTool) {
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.voiceTools = voiceTools;
        this.locationTools = locationTools;
        this.translatorTools =  translatorTools;
        this.imageAnalysisTool = imageAnalysisTool;
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

        log.info("[LLM-Core] 开始处理: text={}", finalText);

        try {
            String promptText = finalText;
            var chatResponse = client.prompt()
                    .user(userSpec -> {
                        if (promptText != null && !promptText.isBlank()) {
                            userSpec.text(promptText);
                        }
                        if (imageUrls != null) {
                            for (String url : imageUrls) {
                                userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(url)));
                            }
                        }
                    })
                    // 3. 把所有工具全部注册进去
                    .tools(weatherTools, imageTools,locationTools, translatorTools,voiceTools, imageAnalysisTool)
                    .call()
                    .chatResponse();

            if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput().getText() == null) {
                throw new RuntimeException("大模型调用失败：模型未返回有效回复");
            }

            String content = chatResponse.getResult().getOutput().getText();
            log.info("[LLM-Core] 处理完成: elapsed={}ms, reply={}", System.currentTimeMillis() - start, content);
            return content;

        } catch (Exception e) {
            log.error("[LLM-Core] 调用异常: {}", e.getMessage(), e);
            throw (RuntimeException) e;
        }
    }

    @Override
    public boolean exitSkill(String userId) {
        return true;
    }
}