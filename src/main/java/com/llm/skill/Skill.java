package com.llm.skill;

/**
 * Skill 统一接口
 * 所有 Skill 必须实现此接口
 */
public interface Skill {

    /**
     * Skill 名称
     */
    String getName();

    /**
     * 匹配关键词列表
     * 用户消息包含任一关键词时触发该 Skill
     */
    String[] getKeywords();

    /**
     * 执行 Skill
     * @param userMessage 用户消息
     * @param userId 用户ID
     * @return 执行结果
     */
    String execute(String userMessage, String userId);
}