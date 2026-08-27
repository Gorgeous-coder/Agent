package com.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 极简知识库条目实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeItem {
    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 用户 ID（用于按用户隔离知识库）
     */
    private String userId;

    /**
     * 文本内容
     */
    private String content;

    /**
     * 创建时间
     */
    private String createdAt;
}