package com.skill.model;

import java.util.List;

/**
 * Skill定义。
 *
 * @param name         Skill唯一名称
 * @param description  Skill用途和触发条件
 * @param version      Skill版本
 * @param enabled      是否启用
 * @param tools        Skill允许使用的Tool方法名
 * @param instructions Skill执行说明，即SKILL.md正文
 */
public record SkillDefinition(
        String name,
        String description,
        String version,
        boolean enabled,
        List<String> tools,
        String instructions
) {

    public SkillDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill名称不能为空"
            );
        }

        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill描述不能为空"
            );
        }

        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill执行说明不能为空"
            );
        }

        name = name.trim();
        description = description.trim();
        version = version == null || version.isBlank()
                ? "1.0.0"
                : version.trim();
        tools = tools == null
                ? List.of()
                : List.copyOf(tools);
        instructions = instructions.trim();
    }
}