package com.rag.mapper;

import com.rag.model.KnowledgeItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 知识库数据库操作 Mapper
 */
@Mapper
public interface KnowledgeItemMapper {

    /**
     * 插入一条知识库内容
     */
    @Insert("""
        INSERT INTO knowledge_item (user_id, content, created_at)
        VALUES (#{userId}, #{content}, #{createdAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(KnowledgeItem item);

    /**
     * 根据用户 ID 查询所有的知识库条目
     */
    @Select("SELECT * FROM knowledge_item WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<KnowledgeItem> findByUserId(@Param("userId") String userId);

    /**
     * 根据 ID 删除某条知识
     */
    @Delete("DELETE FROM knowledge_item WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
}