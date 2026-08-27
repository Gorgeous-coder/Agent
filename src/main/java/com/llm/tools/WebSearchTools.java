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
public class WebSearchTools {

    private final WebSearchService webSearchService;

    @Tool(description = """
            联网搜索互联网公开信息。
            当用户明确要求联网、网上搜索、查资料、寻找网页，
            或问题涉及新闻、价格、政策、排行榜、时效信息、
            资料来源和小众事实时调用。
            返回网页标题、摘要、网站名称和来源链接。
            """)
    public String searchWeb(
            @ToolParam(description = "需要在互联网上搜索的问题或关键词")
            String query) {

        log.info("[WebSearchTool] 被调用: query={}", query);

        try {
            List<WebSearchResult> results =
                    webSearchService.search(query);

            if (results.isEmpty()) {
                return "没有搜索到相关网页，请尝试更换关键词。";
            }

            StringBuilder answer =
                    new StringBuilder("联网搜索结果：\n\n");

            for (int i = 0; i < results.size(); i++) {

                WebSearchResult result =
                        results.get(i);

                String description =
                        hasText(result.summary())
                                ? result.summary()
                                : result.snippet();

                description =
                        cleanAndLimit(description, 600);

                answer.append(i + 1)
                        .append(". ")
                        .append(result.title())
                        .append("\n");

                if (hasText(result.siteName())) {
                    answer.append("网站：")
                            .append(result.siteName())
                            .append("\n");
                }

                if (hasText(result.publishTime())) {
                    answer.append("发布时间：")
                            .append(result.publishTime())
                            .append("\n");
                }

                if (hasText(description)) {
                    answer.append("摘要：")
                            .append(description)
                            .append("\n");
                }

                answer.append("来源：")
                        .append(result.url())
                        .append("\n\n");
            }

            return answer.toString().trim();

        } catch (Exception e) {
            log.error(
                    "[WebSearchTool] 搜索失败: query={}, error={}",
                    query,
                    e.getMessage(),
                    e
            );

            return "联网搜索暂时失败，请稍后重试。";
        }
    }

    private boolean hasText(String value) {
        return value != null
                && !value.isBlank();
    }

    private String cleanAndLimit(
            String value,
            int maxLength) {

        if (value == null) {
            return "";
        }

        String cleaned =
                value.replaceAll("\\s+", " ")
                        .trim();

        if (cleaned.length() <= maxLength) {
            return cleaned;
        }

        return cleaned.substring(0, maxLength)
                + "...";
    }
}
