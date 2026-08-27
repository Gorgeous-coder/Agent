package com.rag.service;

import com.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.rag.mapper.KnowledgeItemMapper;
import com.rag.model.KnowledgeItem;

import java.util.List;

@Slf4j
@Service
public class RagService {

    private final KnowledgeItemMapper itemMapper;
    private final RagProperties ragProperties;

    public RagService(KnowledgeItemMapper itemMapper, RagProperties ragProperties) {
        this.itemMapper = itemMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 核心检索方法：支持开启/关闭 RAG 对比测试
     *
     * @param userId 用户 ID
     * @param question 用户问题
     * @param ragEnabled 是否开启 RAG 开关
     * @return 格式化后的上下文或空字符串
     */
    public String getContext(String userId, String question, boolean ragEnabled) {
        // 如果关闭了 RAG，直接返回空，让大模型自由发挥（用于对比测试）
        if (!ragEnabled) {
            log.info("[KeywordRAG] RAG 开关已关闭，跳过知识库检索");
            return "";
        }

        log.info("[KeywordRAG] RAG 开关已开启，开始关键词检索: question={}", question);

        // 1. 获取该用户的所有知识条目
        List<KnowledgeItem> items = itemMapper.findByUserId(userId);
        if (items == null || items.isEmpty()) {
            return "";
        }

        // 2. 极简关键词匹配：筛选出内容中包含问题关键字的条目
        StringBuilder context = new StringBuilder();
        context.append("📚 【RAG 知识库参考资料】：\n");

        int matchCount = 0;
        int limitTopK = ragProperties.getTopK();

        for (KnowledgeItem item : items) {
            if (item.getContent() != null && item.getContent().contains(question)) {
                context.append("- ").append(item.getContent()).append("\n");
                matchCount++;
                if (matchCount >= limitTopK) break;
            }
        }

        // 如果没有精准包含原句的，兜底取前几条或者返回空
        if (matchCount == 0 && !items.isEmpty()) {
            context.append("- ").append(items.get(0).getContent()).append("\n");
        }

        context.append("请结合以上参考资料回答用户问题。\n---\n");
        return context.toString();
    }
}