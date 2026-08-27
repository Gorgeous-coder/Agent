package com.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.llm.tools.TranslatorTools;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.llm.service.LlmService;
import com.llm.tools.ImageAnalysisTool;
import com.llm.tools.ImageTools;

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
    private final TranslatorTools translatorTools;
    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final ImageAnalysisTool imageAnalysisTool;
    private final UserContext userContext;
    private final Queue<ProcessResult> voiceQueue;

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

        // 每次处理新消息前，先清理旧的图片缓存
        ImageTools.lastGeneratedImageUrl = null;

        // 1. 提取文本、语音识别文字和图片的字节数组
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

        // 提取到文本后，优先判断翻译
        if (text != null && !text.isBlank()) {
            String translateResult = translatorTools.tryHandle(text);
            if (translateResult != null) {
                log.info("[Processor] 翻译结果: {}", translateResult);
                return ProcessResult.text(translateResult, fromUserId);
            }
        }


        var result = new ProcessResult[1];
        String finalText = text;

        // 2. 在用户上下文中通过 AI 处理请求
        userContext.executeAs(fromUserId, () -> {
            try {
                String reply;
                if (!imageBytesList.isEmpty()) {
                    byte[] imageBytes = imageBytesList.get(0);
                    String prompt = (finalText != null && !finalText.isBlank()) ? finalText : "请详细描述这张图片";

                    log.info("[Processor] 触发图片直连分析: userId={}, prompt={}", fromUserId, prompt);
                    reply = imageAnalysisTool.analyzeImage(prompt, imageBytes);
                } else {
                    // 纯文本对话
                    reply = llmService.chat(finalText, List.of(), deepseekClient, fromUserId);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("[Processor] 处理成功: elapsed={}ms, userId={}", elapsed, fromUserId);

                // 3. 检查是否有语音播报结果
                ProcessResult voiceResult = voiceQueue.poll();
                if (voiceResult != null) {
                    result[0] = voiceResult;
                    return;
                }

                // 4. 直接检查 ImageTools 内存中是否暂存了生成的图片 URL
                String cachedUrl = ImageTools.lastGeneratedImageUrl;
                if (cachedUrl != null) {
                    ImageTools.lastGeneratedImageUrl = null; // 用完即清空

                    log.info("[Processor] 检测到新生成的图片 URL，直接下载并转为图片消息返回: {}", cachedUrl);
                    byte[] imageData = downloadImage(cachedUrl);
                    if (imageData != null) {
                        // 直接返回图片格式，机器人会直接展示图片而不是链接
                        result[0] = ProcessResult.image(imageData, fromUserId);
                        return;
                    }
                    log.warn("[Processor] 图片下载失败，降级发送文字提示: userId={}", fromUserId);
                }

                // 5. 默认返回文本
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

    /**
     * 纯净下载逻辑
     */
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