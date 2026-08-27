package com.skill.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import com.skill.model.SkillDefinition;


import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将Skill中声明的工具名称，
 * 转换为Spring AI可以执行的ToolCallback。
 */
@Slf4j
@Component
public class SkillToolResolver {

    /**
     * key是Tool名称，value是真正可执行的ToolCallback。
     */
    private final Map<String, ToolCallback> toolCallbacks;

    public SkillToolResolver() {
        Map<String, ToolCallback> discoveredTools = new LinkedHashMap<>();

        // 方言功能已禁用，暂时不扫描任何工具
        // 后续如果有其他工具需要注册，可以在这里添加

        this.toolCallbacks = Map.copyOf(discoveredTools);

        log.info(
                "[SkillToolResolver] Tool加载完成: count={}, names={}",
                toolCallbacks.size(),
                toolCallbacks.keySet()
        );
    }
    /**
     * 根据Skill声明的工具名称，返回允许使用的工具。
     */
    public ToolCallback[] resolve(
            SkillDefinition skill) {

        List<String> missingTools =
                skill.tools()
                        .stream()
                        .filter(toolName ->
                                !toolCallbacks.containsKey(
                                        toolName
                                ))
                        .toList();

        if (!missingTools.isEmpty()) {
            throw new IllegalStateException(
                    "Skill“"
                            + skill.name()
                            + "”引用了不存在的Tool："
                            + missingTools
            );
        }

        ToolCallback[] resolvedTools =
                skill.tools()
                        .stream()
                        .map(toolCallbacks::get)
                        .toArray(ToolCallback[]::new);

        log.info(
                "[SkillToolResolver] Skill工具解析完成: skill={}, tools={}",
                skill.name(),
                Arrays.stream(resolvedTools)
                        .map(callback ->
                                callback
                                        .getToolDefinition()
                                        .name()
                        )
                        .toList()
        );

        return resolvedTools;
    }

    public int size() {
        return toolCallbacks.size();
    }

    public boolean contains(String toolName) {
        return toolCallbacks.containsKey(toolName);
    }
}