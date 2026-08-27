package com.github.wechat.ilink.sdk.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 工具注册表（业务层，非 SDK 源码）。
 *
 * <p>统一管理所有 Skill，按注册顺序依次调用 {@link Skill#tryHandle(String)}，
 * 第一个返回非 null 的结果即为命中，直接作为回复文本返回。
 *
 * <p>消息路由的使用方式：
 * <pre>
 *   String result = registry.tryAll(text);
 *   if (result != null) {  // Skill 执行链路命中
 *       return result;
 *   }
 *   // 否则进入 RAG / LLM 链路
 * </pre>
 */
public class SkillRegistry {

    private final List<Skill> skills = new ArrayList<>();

    /** 注册一个 Skill */
    public void register(Skill skill) {
        if (skill != null) {
            skills.add(skill);
        }
    }

    /**
     * 按注册顺序尝试所有 Skill，返回第一个命中的执行结果；全部未命中返回 null。
     *
     * @param text 用户消息
     * @return 命中 Skill 的回复文本，或 null
     */
    public String tryAll(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Skill skill : skills) {
            String result = skill.tryHandle(text);
            if (result != null && !result.isEmpty()) {
                System.out.println("[Skill] 命中「" + skill.name() + "」→ " + truncate(result, 60));
                return result;
            }
        }
        return null;
    }

    /** 已注册的 Skill 数量（日志/调试用） */
    public int size() {
        return skills.size();
    }

    /** Skill 名字列表（日志/调试用） */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Skill s : skills) names.add(s.name());
        return names;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
