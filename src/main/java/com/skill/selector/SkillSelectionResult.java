package com.skill.selector;

import com.skill.model.SkillDefinition;

/**
 * Skill 选择结果，区分是否需要二次确认。
 *
 * <ul>
 *   <li>{@code ACTIVATE}：高置信命中，直接激活技能模式。</li>
 *   <li>{@code CONFIRM}：模糊命中，先向用户确认意图后再决定是否激活。</li>
 *   <li>{@code NONE}：未命中任何技能。</li>
 * </ul>
 */
public record SkillSelectionResult(ResultType type, SkillDefinition skill) {

    public enum ResultType { NONE, CONFIRM, ACTIVATE }

    public static SkillSelectionResult none() {
        return new SkillSelectionResult(ResultType.NONE, null);
    }

    public static SkillSelectionResult confirm(SkillDefinition skill) {
        return new SkillSelectionResult(ResultType.CONFIRM, skill);
    }

    public static SkillSelectionResult activate(SkillDefinition skill) {
        return new SkillSelectionResult(ResultType.ACTIVATE, skill);
    }

    public boolean isActivate() {
        return type == ResultType.ACTIVATE;
    }

    public boolean isConfirm() {
        return type == ResultType.CONFIRM;
    }
}
