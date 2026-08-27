package com.llm.tools;

import com.websearch.dto.WebSearchResult;
import com.websearch.service.WebSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final WebSearchService webSearchService;

    @Tool(description = "联网搜索最新信息。当用户询问实时信息、新闻、最新动态、不确定的事情时调用此工具。")
    public String searchWeb(
            @ToolParam(description = "搜索关键词，如'今日新闻'或'人工智能最新进展'") String query
    ) {
        log.info("[WebSearchTool] 搜索: {}", query);
        try {
            List<WebSearchResult> results = webSearchService.search(query);
            if (results.isEmpty()) {
                return "未找到相关信息。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 搜索结果：\n\n");
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                WebSearchResult r = results.get(i);
                sb.append(i + 1).append(". ").append(r.title()).append("\n");
                sb.append("   ").append(r.url()).append("\n");
                sb.append("   ").append(r.snippet()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索失败", e);
            return "联网搜索失败：" + e.getMessage();
        }
    }
}