package com.rag.ingest;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语义切分器：按句子切分 → 向量化 → 相邻相似度骤降处作为语义边界 → 聚合切片。
 * 相比固定 token 数切分，能保证每个切片是完整的语义单元。
 */
@Component
public class SemanticChunker {

    private static final int BATCH_SIZE = 100;   // embedding 分批大小
    private static final double THRESHOLD = 0.7; // 相似度低于此值视为语义边界

    private final EmbeddingModel embeddingModel;

    public SemanticChunker(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<Document> split(Document document) {
        // ① 按句子切分（句号/问号/感叹号/换行）
        List<String> sentences = splitIntoSentences(document.getText());
        if (sentences.size() <= 1) {
            return List.of(document);
        }

        // ② 窗口平滑：Vector(i) = Embed(S[i-1] + S[i] + S[i+1])
        List<String> windowTexts = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String prev = i > 0 ? sentences.get(i - 1) : "";
            String next = i < sentences.size() - 1 ? sentences.get(i + 1) : "";
            windowTexts.add(prev + sentences.get(i) + next);
        }

        // ③ 分批向量化
        List<float[]> vectors = embedInBatches(windowTexts);

        // ④ 算相邻相似度，找语义边界
        List<Integer> splitPoints = new ArrayList<>();
        for (int i = 0; i < vectors.size() - 1; i++) {
            double sim = cosineSimilarity(vectors.get(i), vectors.get(i + 1));
            if (sim < THRESHOLD) {
                splitPoints.add(i + 1); // 第 i 句和第 i+1 句之间切开
            }
        }

        // ⑤ 聚合句子成切片
        List<Document> chunks = new ArrayList<>();
        int start = 0;
        for (int point : splitPoints) {
            chunks.add(new Document(String.join("\n", sentences.subList(start, point))));
            start = point;
        }
        chunks.add(new Document(String.join("\n", sentences.subList(start, sentences.size()))));

        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?\\n])"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<float[]> embedInBatches(List<String> texts) {
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            all.addAll(embeddingModel.embed(batch));
        }
        return all;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}