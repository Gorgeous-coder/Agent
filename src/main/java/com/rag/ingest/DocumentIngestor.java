package com.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识入库：读取文档 → 切片 → 向量化 → 写入 Redis VectorStore。
 * 实现 ApplicationRunner，应用启动时自动执行一次。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "ingest-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestor implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final Resource knowledgeBase;
    private final SemanticChunker semanticChunker;

    public DocumentIngestor(VectorStore vectorStore,
                            SemanticChunker semanticChunker,
                            @Value("classpath:rag/knowledge-base.md") Resource knowledgeBase) {
        this.vectorStore = vectorStore;
        this.semanticChunker = semanticChunker;
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. 读取文档：整个文件作为一个 Document
        TextReader textReader = new TextReader(knowledgeBase);
        textReader.getCustomMetadata().put("source", "knowledge-base.md");
        List<Document> documents = textReader.read();

        // 2. 语义切片：按句子向量相似度骤降处切分，保证语义完整
        List<Document> chunks = documents.stream()
                .flatMap(doc -> semanticChunker.split(doc).stream())
                .toList();

        // 3. 写入向量库：add() 内部自动调用 EmbeddingModel 向量化
        vectorStore.add(chunks);
        log.info("[RAG] 知识库入库完成：{} 个切片", chunks.size());
    }
}