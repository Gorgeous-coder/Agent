package com.websearch.service;

import com.websearch.dto.WebSearchResult;

import java.util.List;

/**
 * 联网搜索服务。
 */
public interface WebSearchService {

    /**
     * 根据关键词搜索互联网公开网页。
     *
     * @param query 用户的搜索内容
     * @return 搜索结果列表
     */
    List<WebSearchResult> search(String query);
}
