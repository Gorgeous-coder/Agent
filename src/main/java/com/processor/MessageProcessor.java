package com.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.llm.service.LlmService;
import com.llm.skill.impl.VideoSummarySkill;
import com.llm.tools.ImageAnalysisTool;
import com.llm.tools.ImageTools;
import com.llm.tools.TranslatorTools;
import com.rag.service.RagService;
import com.skill.model.SkillDefinition;
import com.skill.registry.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@SuppressWarnings("unused")
public class MessageProcessor {

    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final TranslatorTools translatorTools;
    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final ImageAnalysisTool imageAnalysisTool;
    private final UserContext userContext;
    private final Queue<ProcessResult> voiceQueue;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private RagService ragService;

    public MessageProcessor(LlmService llmService,
                            ChatClient deepseekClient,
                            ImageAnalysisTool imageAnalysisTool,
                            UserContext userContext,
                            Queue<ProcessResult> voiceQueue,
                            TranslatorTools translatorTools) {
        this.translatorTools = translatorTools;
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.imageAnalysisTool = imageAnalysisTool;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
    }

    public ProcessResult process(WeixinMessage msg, ILinkClient client) {
        long start = System.currentTimeMillis();
        String fromUserId = msg.getFrom_user_id();

        if (msg.getItem_list() == null) {
            log.info("[Processor] 收到空消息(无item_list): userId={}", fromUserId);
            return null;
        }

        // 每次处理新消息前，先清理旧的图片缓存
        ImageTools.lastGeneratedImageUrl = null;

        // ============================================================
        // 1. 提取文本、语音识别文字和图片的字节数组
        // ============================================================
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

        // ============================================================
        // 第 0 步：翻译优先
        // ============================================================
        if (text != null && !text.isBlank()) {
            String translateResult = translatorTools.tryHandle(text);
            if (translateResult != null) {
                log.info("[Processor] 翻译结果: {}", translateResult);
                return ProcessResult.text(translateResult, fromUserId);
            }
        }

        // ============================================================
// 第 1 步：Skill 匹配
// ============================================================
        if (text != null && !text.isBlank()) {
            // ✅ 优先检查 B站链接（匹配 VideoSummarySkill）
            if (text.contains("bilibili") || text.contains("b23.tv")) {
                log.info("🎬 检测到 B站视频链接");
                // 直接创建并执行 VideoSummarySkill
                VideoSummarySkill videoSkill = new VideoSummarySkill();
                String result = videoSkill.execute(text, fromUserId);
                return ProcessResult.text(result, fromUserId);
            }
        }
        // ============================================================
        // 第 2 步：RAG 检索
        // ============================================================
        if (text != null && !text.isBlank()) {
            String ragContext = ragService.getContext(fromUserId, text, true);
            if (ragContext != null && !ragContext.isEmpty()) {
                log.info("📚 [路由-2] RAG 匹配成功，增强 Prompt");
                String enhancedPrompt = buildRagPrompt(text, ragContext);
                if (!imageBytesList.isEmpty()) {
                    byte[] imageBytes = imageBytesList.get(0);
                    String reply = imageAnalysisTool.analyzeImage(enhancedPrompt, imageBytes);
                    return ProcessResult.text(reply, fromUserId);
                }
                String reply = llmService.chat(enhancedPrompt, List.of(), deepseekClient, fromUserId);
                return ProcessResult.text(reply, fromUserId);
            }
        }

        // ============================================================
        // 第 3 步：LLM 兜底闲聊
        // ============================================================
        log.info("💬 [路由-3] 走 LLM 兜底闲聊");

        var result = new ProcessResult[1];
        String finalText = text;

        userContext.executeAs(fromUserId, () -> {
            try {
                String reply;
                if (!imageBytesList.isEmpty()) {
                    byte[] imageBytes = imageBytesList.get(0);
                    String prompt = (finalText != null && !finalText.isBlank()) ? finalText : "请详细描述这张图片";
                    reply = imageAnalysisTool.analyzeImage(prompt, imageBytes);
                } else {
                    reply = llmService.chat(finalText, List.of(), deepseekClient, fromUserId);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("[Processor] 处理成功: elapsed={}ms, userId={}", elapsed, fromUserId);

                // ⭐⭐⭐ 核心修改：只有明确要语音才返回语音 ⭐⭐⭐
                ProcessResult voiceResult = voiceQueue.poll();
                if (voiceResult != null) {
                    boolean wantsVoice = false;
                    if (finalText != null && !finalText.isBlank()) {
                        String lowerText = finalText.toLowerCase();
                        wantsVoice = lowerText.contains("语音") ||
                                lowerText.contains("播报") ||
                                lowerText.contains("说一遍") ||
                                lowerText.contains("念一遍") ||
                                lowerText.contains("读一遍");
                    }

                    if (wantsVoice) {
                        log.info("[Processor] 🎤 用户明确要求语音，返回语音");
                        result[0] = voiceResult;
                        return;
                    } else {
                        log.info("[Processor] 📝 跳过语音，返回文字");
                        String textReply = voiceResult.text() != null ? voiceResult.text() : "已生成结果";
                        result[0] = ProcessResult.text(textReply, fromUserId);
                        return;
                    }
                }

                // 检查是否有图片
                String cachedUrl = ImageTools.lastGeneratedImageUrl;
                if (cachedUrl != null) {
                    ImageTools.lastGeneratedImageUrl = null;
                    byte[] imageData = downloadImage(cachedUrl);
                    if (imageData != null) {
                        result[0] = ProcessResult.image(imageData, fromUserId);
                        return;
                    }
                }

                result[0] = ProcessResult.text(reply, fromUserId);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("[Processor] 处理失败: elapsed={}ms, userId={}, error={}", elapsed, fromUserId, e.getMessage(), e);
                result[0] = ProcessResult.text("处理请求时发生错误：" + e.getMessage(), fromUserId);
            }
        });return result[0];
    }

    /**
     * 执行 Skill
     */
    private String executeSkill(SkillDefinition skill, String userMessage, String userId) {
        // TODO: 根据 Skill 定义执行具体逻辑
        // 这里需要调用 SkillToolResolver 或直接执行
        log.info("[Skill] 执行: {}, userId={}", skill.name(), userId);
        return "✅ 执行技能: " + skill.name() + "\n" + skill.description();
    }

    private String buildRagPrompt(String userQuestion, String ragContext) {
        return """
            请基于以下知识库信息回答用户的问题。

            ===== 知识库信息 =====
            %s
            ======================

            用户问题：%s

            请基于知识库内容给出准确、清晰的回答。如果知识库中没有相关信息，请如实告知用户。
            """.formatted(ragContext, userQuestion);
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
                return null;
            }
            byte[] data = response.body().bytes();
            log.info("[Processor] 图片下载成功: {}KB", data.length / 1024);
            return data;
        } catch (Exception e) {
            log.error("[Processor] 下载图片异常: {}", e.getMessage());
            return null;
        }
    }
}