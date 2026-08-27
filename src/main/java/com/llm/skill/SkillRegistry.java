package com.llm.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 注册中心
 * 自动扫描所有 Skill 并注册，支持关键词匹配
 */
@Slf4j
@Component
public class SkillRegistry {

    private final Map<String, Skill> keywordToSkill = new HashMap<>();
    private final Map<String, Skill> skillMap = new HashMap<>();

    @Autowired(required = false)
    private List<Skill> skills;

    @PostConstruct
    public void init() {
        if (skills == null || skills.isEmpty()) {
            log.info("⚠️ 未发现任何 Skill");
            return;
        }

        for (Skill skill : skills) {
            skillMap.put(skill.getName(), skill);
            for (String keyword : skill.getKeywords()) {
                keywordToSkill.put(keyword, skill);
                log.info("✅ 注册 Skill: {} → 关键词: {}", skill.getName(), keyword);
            }
        }
        log.info("✅ 共注册 {} 个 Skill", skills.size());
    }

    /**
     * 根据用户消息匹配 Skill
     * @return 匹配到的 Skill，如果没有则返回 null
     */
    public Skill match(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Skill> entry : keywordToSkill.entrySet()) {
            if (userMessage.contains(entry.getKey())) {
                log.info("🎯 匹配到 Skill: {} (关键词: {})", entry.getValue().getName(), entry.getKey());
                return entry.getValue();
            }
        }
        return null;
    }

    public List<Skill> getAllSkills() {
        return skills != null ? List.copyOf(skills) : List.of();
    }

    public Skill getSkillByName(String name) {
        return skillMap.get(name);
    }
}