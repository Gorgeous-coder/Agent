package com.skill.selector;

/**
 * 根据用户消息选择需要使用的Skill。
 */
public interface SkillSelector {
    /**
     * @param message 用户发送的消息
     * @return 选择结果（激活/待确认/未命中）
     */
    SkillSelectionResult select(String message);
}
