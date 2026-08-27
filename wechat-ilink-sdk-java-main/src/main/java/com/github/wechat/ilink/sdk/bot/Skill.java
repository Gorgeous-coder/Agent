package com.github.wechat.ilink.sdk.bot;

/**
 * Skill 工具接口（业务层，非 SDK 源码）。
 *
 * <p>一个 Skill = 一个可被消息路由命中的工具能力。
 * 约定与现有 {@code Weather}/{@code Translator}/{@code Calculator} 一致：
 * <ul>
 *   <li>{@link #tryHandle(String)} 返回 {@code null} 表示"未命中本 Skill"；</li>
 *   <li>返回非 {@code null} 表示命中，返回值就是直接回复用户的文本。</li>
 * </ul>
 *
 * <p>实现类只需写意图识别 + 执行逻辑，路由编排由 {@link SkillRegistry} 统一完成。
 */
public interface Skill {

    /** 技能名称（用于日志展示，如 "计算器"、"单位换算"） */
    String name();

    /**
     * 尝试处理用户消息。
     *
     * @param text 用户原始消息（文字或语音转文字）
     * @return 命中时返回可直接回复的文本；未命中返回 {@code null}
     */
    String tryHandle(String text);
}
