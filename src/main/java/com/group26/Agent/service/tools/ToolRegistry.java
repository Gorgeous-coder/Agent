package com.group26.Agent.service.tools;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, Tool> toolMap = new HashMap<>();

    //注册工具
    public void register(Tool tool) {
        toolMap.put(tool.name(),tool);
        System.out.println("注册工具"+tool.name());
    }
    //根据名称获取工具
    public Tool getTool(String name) {
        return toolMap.get(name);
    }
    //获取所有工具
    public Map<String, Tool> getToolMap() {
        return toolMap;
    }
}
