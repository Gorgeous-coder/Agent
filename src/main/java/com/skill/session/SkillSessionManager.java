package com.skill.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 活跃会话管理（userId → 会话）。
 *
 * <p>由 {@code LlmServiceImpl} 读写实现会话保持（10 分钟滑动 TTL），
 * 猎聘工具在任务取消、计划永久停止时清除会话，让用户无需等 TTL 过期即可回到普通对话。</p>
 *
 * <p>另维护一份"待确认" Skill：模糊命中时不直接激活，先记录候选 Skill，
 * 待用户确认后才由 {@code LlmServiceImpl} 转为正式会话。</p>
 */
@Component
public class SkillSessionManager {

    public record SkillSession(String skillName, long lastActiveAt) {}

    private final Map<String, SkillSession> activeSkills = new ConcurrentHashMap<>();
    private final Map<String, SkillSession> pendingSkills = new ConcurrentHashMap<>();

    /** 激活或续期 Skill 会话。 */
    public void activate(String userId, String skillName) {
        activeSkills.put(userId, new SkillSession(skillName, System.currentTimeMillis()));
    }

    /** 查询活跃会话；无则返回 {@code null}。 */
    public SkillSession get(String userId) {
        return activeSkills.get(userId);
    }

    /** 清除活跃会话并返回它；无则返回 {@code null}。 */
    public SkillSession remove(String userId) {
        return activeSkills.remove(userId);
    }

    /** 记录待确认的候选 Skill。 */
    public void setPending(String userId, String skillName) {
        pendingSkills.put(userId, new SkillSession(skillName, System.currentTimeMillis()));
    }

    /**
     * 原始读取待确认 Skill，不做 TTL 检查；无则返回 {@code null}。
     * 生产路径请使用带 TTL 的 {@link #getPending(String, long)}，
     * 避免几小时前的"待确认"被一句"好"误激活。
     */
    public SkillSession getPending(String userId) {
        return pendingSkills.get(userId);
    }

    /**
     * 查询 TTL 内有效的待确认 Skill；已过期则惰性清除并返回 {@code null}。
     */
    public SkillSession getPending(String userId, long ttlMs) {
        SkillSession pending = pendingSkills.get(userId);
        if (pending == null) return null;
        if (System.currentTimeMillis() - pending.lastActiveAt() >= ttlMs) {
            pendingSkills.remove(userId, pending);
            return null;
        }
        return pending;
    }

    /** 清除待确认 Skill 并返回它；无则返回 {@code null}。 */
    public SkillSession removePending(String userId) {
        return pendingSkills.remove(userId);
    }
}
