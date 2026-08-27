package com.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.llm.service.LlmService;
import com.llm.tools.ImageAnalysisTool;
import com.llm.tools.ImageTools;

import com.skill.selector.SkillSelector;
import com.skill.selector.SkillSelectionResult;
import com.skill.session.SkillSessionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * 微信消息处理器：专注于文字、单张图片、语音及天气等 @Tool 自动触发。
 */
@Slf4j
@Component
@SuppressWarnings("unused")
public class MessageProcessor {

    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final ImageAnalysisTool imageAnalysisTool;
    private final UserContext userContext;
    private final Queue<ProcessResult> voiceQueue;

    private final SkillSelector skillSelector;
    private final SkillSessionManager skillSessionManager;

    public MessageProcessor(LlmService llmService,
                            ChatClient deepseekClient,
                            ImageAnalysisTool imageAnalysisTool,
                            UserContext userContext,
                            Queue<ProcessResult> voiceQueue,
                            SkillSelector skillSelector,
                            SkillSessionManager skillSessionManager) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.imageAnalysisTool = imageAnalysisTool;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
        this.skillSelector = skillSelector;
        this.skillSessionManager = skillSessionManager;
    }

    /**
     * 处理一条微信消息，同步返回处理结果。
     */
    public ProcessResult process(WeixinMessage msg, ILinkClient client) {
        long start = System.currentTimeMillis();
        String fromUserId = msg.getFrom_user_id();

        if (msg.getItem_list() == null) {
            log.info("[Processor] 收到空消息(无item_list): userId={}", fromUserId);
            return null;
        }

        ImageTools.lastGeneratedImageUrl = null;

        String text = extractText(msg);
        String voiceText = extractVoiceText(msg);
        List<byte[]> imageBytesList = extractImageBytes(msg, client);

        if (voiceText != null && !voiceText.isBlank()) {
            text = (text != null) ? text + " " + voiceText : voiceText;
        }

        if (text == null && imageBytesList.isEmpty()) {
            log.info("[Processor] 不支持的消息类型: userId={}", fromUserId);
            return null;
        }

        var result = new ProcessResult[1];
        String finalText = text;

        userContext.executeAs(fromUserId, () -> {
            try {
                if (finalText != null && !finalText.isBlank()) {
                    if (finalText.contains("退出方言模式") || finalText.contains("退出方言助手") || finalText.contains("关闭方言模式")) {
                        llmService.exitSkill(fromUserId);
                        result[0] = ProcessResult.text("已退出方言助手模式，已回到标准普通话对话。", fromUserId);
                        return;
                    }

                    if (skillSessionManager.get(fromUserId) == null) {
                        SkillSelectionResult selectionResult = skillSelector.select(finalText);
                        if (selectionResult.isActivate()) {
                            String skillName = selectionResult.skill().name();
                            skillSessionManager.activate(fromUserId, skillName);
                            log.info("[Processor] 用户触发并激活技能: userId={}, skill={}", fromUserId, skillName);
                        } else if (selectionResult.isConfirm()) {
                            skillSessionManager.setPending(fromUserId, selectionResult.skill().name());
                            result[0] = ProcessResult.text("检测到您可能需要方言语音助手服务，请回复\"确认\"开启。", fromUserId);
                            return;
                        }
                    }
                }

                String reply;
                if (!imageBytesList.isEmpty()) {
                    byte[] imageBytes = imageBytesList.getFirst();
                    String prompt = (finalText != null && !finalText.isBlank()) ? finalText : "请详细描述这张图片";

                    log.info("[Processor] 触发图片直连分析: userId={}, prompt={}", fromUserId, prompt);
                    reply = imageAnalysisTool.analyzeImage(prompt, imageBytes);
                } else {
                    reply = llmService.chat(finalText, List.of(), deepseekClient, fromUserId);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("[Processor] 处理成功: elapsed={}ms, userId={}", elapsed, fromUserId);

                ProcessResult voiceResult = voiceQueue.poll();
                if (voiceResult != null) {
                    result[0] = voiceResult;
                    return;
                }

                String cachedUrl = ImageTools.lastGeneratedImageUrl;
                if (cachedUrl != null) {
                    ImageTools.lastGeneratedImageUrl = null;

                    log.info("[Processor] 检测到新生成的图片 URL，直接下载并转为图片消息返回: {}", cachedUrl);
                    byte[] imageData = downloadImage(cachedUrl);
                    if (imageData != null) {
                        result[0] = ProcessResult.image(imageData, fromUserId);
                        return;
                    }
                    log.warn("[Processor] 图片下载失败，降级发送文字提示: userId={}", fromUserId);
                }

                result[0] = ProcessResult.text(reply, fromUserId);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("[Processor] 处理失败: elapsed={}ms, userId={}, error={}", elapsed, fromUserId, e.getMessage(), e);
                result[0] = ProcessResult.text("处理请求时发生错误：" + e.getMessage(), fromUserId);
            }
        });

        return result[0];
    }

    private String extractText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private String extractVoiceText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null && item.getVoice_item().getText() != null) {
                return item.getVoice_item().getText();
            }
        }
        return null;
    }

    private List<byte[]> extractImageBytes(WeixinMessage msg, ILinkClient client) {
        List<byte[]> imageBytesList = new ArrayList<>();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                CDNMedia media = item.getImage_item().getMedia();
                if (media != null) {
                    try {
                        byte[] imageBytes = client.downloadMedia(media);
                        if (imageBytes != null && imageBytes.length > 0) {
                            imageBytesList.add(imageBytes);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("CDN 下载媒体失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        return imageBytesList;
    }

    private byte[] downloadImage(String imageUrl) {
        String cleanUrl = imageUrl != null ? imageUrl.trim() : "";
        if (cleanUrl.isEmpty()) {
            return null;
        }

        Request request = new Request.Builder()
                .url(cleanUrl)
                .get()
                .build();

        try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "empty";
                log.error("[Processor] 下载图片失败: url={}, httpCode={}, error={}", cleanUrl, response.code(), errorBody);
                return null;
            }
            byte[] data = response.body().bytes();
            log.info("[Processor] 图片下载成功: url={}, size={}KB", cleanUrl, data.length / 1024);
            return data;
        } catch (Exception e) {
            log.error("[Processor] 下载图片异常: url={}, error={}", cleanUrl, e.getMessage(), e);
            return null;
        }
    }
}
