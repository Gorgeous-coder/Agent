package com.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
class RetrievalServiceTest {

    @Autowired
    private RetrievalService retrievalService;

    @Test
    void retrieve() {
        List<Document> results = retrievalService.retrieve("Agent 项目用什么向量模型？", 3);
        log.info("检索到 {} 条结果", results.size());
        results.forEach(doc -> log.info("--- 命中内容 ---\n{}", doc.getText()));
    }
}