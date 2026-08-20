package com.llm.service;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

public interface LlmService {

    /**
     * 发送聊天请求。
     *
     * @param text      用户文本，同时也是 Skill 路由的依据
     * @param imageUrls 图片 URL 列表
     * @param client    ChatClient 实例
     * @param userId    用户 ID
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId);

    /**
     * 带上下文前缀的聊天请求。
     *
     * <p>{@code systemContext} 会作为系统消息前置注入，用于传递文档内容等上下文；
     * {@code text} 仅用于 Skill 路由和用户消息，不会混入上下文，避免误触发 Skill。</p>
     *
     * @param text          用户文本，Skill 路由依据
     * @param imageUrls     图片 URL 列表
     * @param client        ChatClient 实例
     * @param userId        用户 ID
     * @param systemContext 系统消息上下文（文档内容等）；为 {@code null} 时不注入
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext);

    /**
     * 带技能路由开关的聊天请求。
     *
     * <p>{@code skillEnabled=false} 用于系统生成的消息（如定时提醒）：跳过技能匹配，
     * 不激活技能、不消费/写入待确认状态，工具集固定为通用工具，避免提醒被用户
     * 活跃的技能会话劫持。用户的技能会话状态保持不变。</p>
     *
     * @param text          消息文本（系统生成时为提醒内容）
     * @param imageUrls     图片 URL 列表
     * @param client        ChatClient 实例
     * @param userId        用户 ID
     * @param systemContext 系统消息上下文；为 {@code null} 时不注入
     * @param skillEnabled  是否参与技能匹配与会话保持
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId,
                String systemContext, boolean skillEnabled);

    /**
     * 手动清除指定用户的活跃 Skill 会话，返回是否确实存在活跃会话。
     */
    boolean exitSkill(String userId);
}
