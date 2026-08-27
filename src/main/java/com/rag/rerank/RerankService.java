package com.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 重排：把 query 和每篇候选文档拼在一起打分，重排后取 Top-N。
 * 调用 SiliconFlow 的 /v1/rerank 接口（BAAI/bge-reranker-v2-m3）。
 */
@Slf4j
@Service
public class RerankService {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public RerankService(RestTemplate restTemplate,
                         @Value("${spring.ai.openai.api-key}") String apiKey,
                         @Value("${spring.ai.openai.base-url}") String baseUrl,
                         @Value("${spring.ai.openai.rerank.model:BAAI/bge-reranker-v2-m3}") String model) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public List<Document> rerank(String query, List<Document> documents, int topN) {
        // 1. 构造请求体：query + 候选文档文本列表
        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", documents.stream().map(Document::getText).toList(),
                "top_n", topN,
                "return_documents", false
        );

        // 2. 设置请求头：JSON + Bearer Token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 3. 调用 SiliconFlow /v1/rerank
        RerankResponse response = restTemplate.postForObject(
                baseUrl + "/rerank",
                new HttpEntity<>(body, headers),
                RerankResponse.class
        );

        // 4. 按返回的 index 重排文档（results 已按分数降序）
        List<Document> reranked = response.results().stream()
                .map(r -> documents.get(r.index()))
                .toList();

        log.info("[RAG] 重排完成：{} 篇 → 取 Top-{}", documents.size(), reranked.size());
        return reranked;
    }

    /** SiliconFlow rerank 响应结构 */
    public record RerankResponse(List<RerankResult> results) {
        public record RerankResult(int index, double relevance_score) {}
    }
}