package com.rag.retrieve;

import com.rag.rerank.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 语义检索：向量召回 Top-K 候选，再用 Cross-Encoder 重排取最相关的 Top-N。
 */
@Slf4j
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    public RetrievalService(VectorStore vectorStore, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    public List<Document> retrieve(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.info("[RAG] 检索到 {} 条相关文档", results.size());
        return results;//不是向量，返回的是 `Document` 文档对象（文本切片）
    }

    /**
     * 召回 + 重排：先向量检索召回 recallK 篇候选，再用 Cross-Encoder 精排取 topN 篇。
     */
    //都是文本，不是向量。 向量在整个流程里只是"内部中间产物"，被封装起来了
    public List<Document> retrieveWithRerank(String query, int recallK, int topN) {
        // 1. 召回：多捞一些候选（recallK），保证召回率
        List<Document> candidates = retrieve(query, recallK);

        // 2. 精排：Cross-Encoder 逐篇打分，重排后取 Top-N
        return rerankService.rerank(query, candidates, topN);
    }
}