package com.llm.service;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

public interface LlmService {

    /**
     * 发送聊天请求。
     *
     * @param text      用户文本
     * @param imageUrls 图片 URL 列表
     * @param client    ChatClient 实例
     * @param userId    用户 ID
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId);

    /**
     * 带上下文前缀的聊天请求。
     *
     * <p>{@code systemContext} 会作为系统消息前置注入，用于传递文档内容等上下文；
     * {@code text} 作为用户消息传入，不混入系统上下文。</p>
     *
     * @param text          用户文本
     * @param imageUrls     图片 URL 列表
     * @param client        ChatClient 实例
     * @param userId        用户 ID
     * @param systemContext 系统消息上下文（文档内容等）；为 {@code null} 时不注入
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext);

    String chat(String text, List<String> imageUrls, ChatClient client, String userId,
                String systemContext, boolean skillEnabled);

    boolean exitSkill(String userId);
}
