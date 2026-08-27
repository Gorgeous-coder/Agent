package com.llm.skill;

import lombok.extern.slf4j.Slf4j;

/**
 * Skill 抽象基类
 * 提供通用的日志和执行模板
 */
@Slf4j
public abstract class BaseSkill implements Skill {

    @Override
    public String execute(String userMessage, String userId) {
        log.info("[Skill] 执行: {}, userId={}", getName(), userId);
        long start = System.currentTimeMillis();

        try {
            String result = doExecute(userMessage, userId);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Skill] 执行完成: {}, 耗时: {}ms", getName(), elapsed);
            return result;
        } catch (Exception e) {
            log.error("[Skill] 执行失败: {}", getName(), e);
            return "❌ " + getName() + " 执行失败：" + e.getMessage();
        }
    }

    /**
     * 具体执行逻辑，由子类实现
     */
    protected abstract String doExecute(String userMessage, String userId);
}