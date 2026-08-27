package com.skill.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.skill.model.SkillDefinition;
import com.skill.registry.SkillRegistry;

import java.util.Locale;
import java.util.Set;

/**
 * Skill 选择器（基于关键词与技能描述匹配）。
 * 已移除对外部 Embedding 服务的强依赖，确保项目编译即运行。
 */
@Slf4j
@Component
public class EmbeddingSkillSelector implements SkillSelector {

    /** 降级时忽略的通用词 */
    private static final Set<String> KEYWORD_BLACKLIST = Set.of("助手");

    /** 方言助手专属强特征词：命中这些词直接激活技能 */
    private static final Set<String> STRONG_2CHAR_KEYWORDS = Set.of("方言", "粤语", "四川", "播报", "长辈");

    private final SkillRegistry skillRegistry;

    public EmbeddingSkillSelector(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public SkillSelectionResult select(String message) {
        if (message == null || message.isBlank()) {
            return SkillSelectionResult.none();
        }

        return selectByKeywords(message.trim());
    }

    /**
     * 通过关键词和技能描述子串匹配来识别意图
     */
    private SkillSelectionResult selectByKeywords(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);

        SkillDefinition bestSkill = null;
        int bestMatchLen = 0;

        for (SkillDefinition skill : skillRegistry.findAllEnabled()) {
            int matchLen = matchScore(skill, normalized);
            if (matchLen > bestMatchLen) {
                bestMatchLen = matchLen;
                bestSkill = skill;
            }
        }

        if (bestSkill != null && bestMatchLen > 0) {
            log.info("[SkillSelector] 技能命中: name={}, matchLen={}, preview={}",
                    bestSkill.name(), bestMatchLen, preview(message));
            return SkillSelectionResult.activate(bestSkill);
        }

        log.debug("[SkillSelector] 未命中任何技能: preview={}", preview(message));
        return SkillSelectionResult.none();
    }

    private int matchScore(SkillDefinition skill, String normalizedMessage) {
        String source = (skill.name() + " " + skill.description()).toLowerCase(Locale.ROOT);
        String[] segments = source.split("[，。！？、；：（）\\s,.!?;:()\\[\\]【】\\-—…]+");

        int maxLen = 0;
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.length() < 2) continue;

            if (!KEYWORD_BLACKLIST.contains(trimmed)
                    && (trimmed.length() >= 3 || STRONG_2CHAR_KEYWORDS.contains(trimmed))
                    && normalizedMessage.contains(trimmed)) {
                maxLen = Math.max(maxLen, trimmed.length());
                continue;
            }

            if (trimmed.length() > 3) {
                int subMax = Math.min(4, trimmed.length() - 1);
                for (int len = subMax; len >= 3; len--) {
                    for (int i = 0; i <= trimmed.length() - len; i++) {
                        String sub = trimmed.substring(i, i + len);
                        if (KEYWORD_BLACKLIST.contains(sub)) continue;
                        if (normalizedMessage.contains(sub)) {
                            maxLen = Math.max(maxLen, len);
                        }
                    }
                }
                for (String kw : STRONG_2CHAR_KEYWORDS) {
                    if (!KEYWORD_BLACKLIST.contains(kw)
                            && trimmed.contains(kw)
                            && normalizedMessage.contains(kw)) {
                        maxLen = Math.max(maxLen, 2);
                    }
                }
            }
        }

        return maxLen;
    }

    private String preview(String text) {
        if (text == null) return "null";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}