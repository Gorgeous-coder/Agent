package com.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConversationHistoryService {

    private static final int MAX_HISTORY_TURNS = 10;  // 保留最近 10 轮对话

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 保存一条对话记录
     */
    public void saveMessage(String userId, String role, String content, String messageType) {
        try {
            String sql = """
                INSERT INTO conversation_message (user_id, role, content, message_type, created_at)
                VALUES (?, ?, ?, ?, datetime('now', 'localtime'))
                """;
            jdbcTemplate.update(sql, userId, role, content, messageType != null ? messageType : "text");
            log.debug("[Conversation] 保存消息: userId={}, role={}, content={}", userId, role,
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
        } catch (Exception e) {
            log.warn("[Conversation] 保存消息失败: {}", e.getMessage());
        }
    }

    /**
     * 获取用户的对话历史（用于 LLM 上下文）
     */
    public List<Map<String, String>> getHistory(String userId) {
        try {
            String sql = """
                SELECT role, content FROM conversation_message
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, MAX_HISTORY_TURNS * 2);

            // 反转顺序（从旧到新）
            List<Map<String, String>> history = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0; i--) {
                Map<String, Object> row = rows.get(i);
                Map<String, String> msg = new java.util.HashMap<>();
                msg.put("role", row.get("role").toString());
                msg.put("content", row.get("content").toString());
                history.add(msg);
            }

            log.debug("[Conversation] 获取历史: userId={}, count={}", userId, history.size());
            return history;

        } catch (Exception e) {
            log.warn("[Conversation] 获取历史失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 清空用户的对话历史
     */
    public void clearHistory(String userId) {
        try {
            jdbcTemplate.update("DELETE FROM conversation_message WHERE user_id = ?", userId);
            log.info("[Conversation] 清空历史: userId={}", userId);
        } catch (Exception e) {
            log.warn("[Conversation] 清空历史失败: {}", e.getMessage());
        }
    }

    /**
     * 清空所有用户的对话历史
     */
    public void clearAllHistory() {
        try {
            int deleted = jdbcTemplate.update("DELETE FROM conversation_message");
            log.info("[Conversation] 清空所有历史: {} 条", deleted);
        } catch (Exception e) {
            log.warn("[Conversation] 清空所有历史失败: {}", e.getMessage());
        }
    }
}