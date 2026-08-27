package com.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.llm.service.LlmService;
import com.llm.tools.*;
import com.rag.tool.DialectAssistantTools;
import com.skill.registry.SkillRegistry;
import com.skill.tool.SkillToolResolver;
import com.skill.session.SkillSessionManager;
import com.skill.model.SkillDefinition;

import java.net.URI;
import java.util.List;

@Slf4j
@Service
@SuppressWarnings("unused")
public class LlmServiceImpl implements LlmService {

    // 1. 所有的通用工具
    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VoiceTools voiceTools;
    private final LocationTools locationTools;
<<<<<<< Updated upstream
=======
    private final TranslatorTools translatorTools;
>>>>>>> Stashed changes
    private final ImageAnalysisTool imageAnalysisTool;

    private final SkillRegistry skillRegistry;
    private final SkillToolResolver skillToolResolver;
    private final SkillSessionManager skillSessionManager;

    public LlmServiceImpl(WeatherTools weatherTools,
                          ImageTools imageTools,
                          VoiceTools voiceTools,
                          LocationTools locationTools,
<<<<<<< Updated upstream
                          ImageAnalysisTool imageAnalysisTool) {
=======
                          TranslatorTools translatorTools,
                          ImageAnalysisTool imageAnalysisTool,
                          SkillRegistry skillRegistry,
                          SkillToolResolver skillToolResolver,
                          SkillSessionManager skillSessionManager) {
>>>>>>> Stashed changes
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.voiceTools = voiceTools;
        this.locationTools = locationTools;
<<<<<<< Updated upstream
=======
        this.translatorTools = translatorTools;
>>>>>>> Stashed changes
        this.imageAnalysisTool = imageAnalysisTool;
        this.skillRegistry = skillRegistry;
        this.skillToolResolver = skillToolResolver;
        this.skillSessionManager = skillSessionManager;
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
            // 2. 动态获取当前用户激活的 Skill 工具
            ToolCallback[] dynamicTools = new ToolCallback[0];
            if (skillEnabled) {
                SkillSessionManager.SkillSession session = skillSessionManager.get(userId);
                if (session != null) {
                    SkillDefinition skill = skillRegistry.findEnabledByName(session.skillName()).orElse(null);
                    if (skill != null) {
                        dynamicTools = skillToolResolver.resolve(skill);
                        log.info("[LLM-Core] 当前用户已激活技能: {}, 动态加载工具数: {}", skill.name(), dynamicTools.length);
                    }
                }
            }

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
<<<<<<< Updated upstream
                    // 3. 把所有工具全部注册进去
                    .tools(weatherTools, imageTools, voiceTools, locationTools, imageAnalysisTool)
=======
                    .tools(weatherTools, imageTools, locationTools, translatorTools, imageAnalysisTool, voiceTools)
                    .tools(dynamicTools)
>>>>>>> Stashed changes
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
        skillSessionManager.remove(userId);
        log.info("[LLM-Core] 用户已退出当前技能模式: userId={}", userId);
        return true;
    }
}