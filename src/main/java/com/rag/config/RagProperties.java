package com.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置类
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 是否启用 RAG 功能（核心开关：用于开启/关闭 RAG 对比测试）。
     */
    private boolean enabled = true;

    /**
     * 每次检索返回的最多片段数量。
     */
    private int topK = 3;
}