package com.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.llm.service.LlmService;
import com.llm.tools.*;
import com.storage.StorageProperties;

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
    private final StorageProperties storageProperties;

    public LlmServiceImpl(WeatherTools weatherTools,
                          ImageTools imageTools,
                          VoiceTools voiceTools,
                          LocationTools locationTools,
                          TranslatorTools translatorTools,
                          ImageAnalysisTool imageAnalysisTool,
                          StorageProperties storageProperties) {
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.voiceTools = voiceTools;
        this.locationTools = locationTools;
        this.translatorTools =  translatorTools;
        this.imageAnalysisTool = imageAnalysisTool;
        this.storageProperties = storageProperties;
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId) {
        return chat(text, imageUrls, client, userId, null);//这里是没有上下文的核心原因？
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext) {
        long start = System.currentTimeMillis();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        String finalText = text;


        if ((finalText == null || finalText.isBlank()) && hasImages) {
            finalText = "请描述这些图片";
        }

        log.info("[LLM-Core] 开始处理: text={}", finalText);

        try {
            String promptText = finalText;
            var request = client.prompt();//创建一个**空的 prompt 请求对象**，**还没有真正发网络请求**。
//            deepseekClient（共享 Bean）
//                ├── defaultTools(fileSystemTool)   ← 客户端级：deepseekClient 的【所有】请求都带
//                └── 某次请求
//                    └── .tools(weatherTools, ...)  ← 请求级：只有【这一次】请求带
            if (userId != null && !userId.isBlank()) {
                String memoUserId = userId.indexOf('@') >= 0
                        ? userId.substring(0, userId.indexOf('@'))
                        : userId;
                request.system("当前用户ID: " + memoUserId + "\n备忘目录: " + storageProperties.memoDir());
                //1. userId 去掉 @ 后的微信后缀，保证备忘文件名统一（不依赖模型自行截断）
                //2. 备忘目录搬进可配置根目录后，模型"猜"不出绝对路径了，必须喂给它。
            }
            var chatResponse = request
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
                    // 把所有工具全部注册进去
                    .tools(weatherTools, imageTools, locationTools, translatorTools, voiceTools, imageAnalysisTool)
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
}