package com.websearch.dto;

/**
 * 单条网页搜索结果。
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String summary,
        String siteName,
        String publishTime
) {
}
