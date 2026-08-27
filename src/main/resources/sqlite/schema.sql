-- ================================================================================
-- 数据库 Schema
-- 引擎: SQLite（jdbc:sqlite:./work/sqlite/conversation.db）
-- 规范: 所有时间字段用 TEXT 存储，默认值 CURRENT_TIMESTAMP
-- ================================================================================

-- ================================================================================
-- 1. conversation_message — 对话历史表
-- 用途: 按用户隔离的滑动窗口记忆
-- ================================================================================
CREATE TABLE IF NOT EXISTS conversation_message (
                                                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                                    user_id     TEXT    NOT NULL,             -- 用户 ID
                                                    role        TEXT    NOT NULL              -- 角色: user / assistant / system / tool
                                                        CHECK (role IN ('user', 'assistant', 'system', 'tool')),
                                                    content     TEXT    NOT NULL,             -- 消息内容
                                                    message_type TEXT  NOT NULL DEFAULT 'text', -- text / image / voice / video
                                                    model_name  TEXT,                         -- 回复使用的模型
                                                    created_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conversation_message_user_id_id
    ON conversation_message (user_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_conversation_message_created_at
    ON conversation_message (created_at);


-- ================================================================================
-- 2. knowledge_item — 极简 RAG 知识库表
-- 用途: 存储用户的知识文本，供极简关键词检索使用
-- ================================================================================
CREATE TABLE IF NOT EXISTS knowledge_item (
                                              id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                              user_id     TEXT    NOT NULL,             -- 用户 ID
                                              content     TEXT    NOT NULL,             -- 知识条目文本内容
                                              created_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_item_user_id
    ON knowledge_item(user_id);


-- ================================================================================
-- 3. user_dialect_preference — 用户方言偏好表
-- 用途: 保存用户的方言交互偏好
-- ================================================================================
CREATE TABLE IF NOT EXISTS user_dialect_preference (
                                                       id           INTEGER PRIMARY KEY AUTOINCREMENT,
                                                       user_id      TEXT    NOT NULL UNIQUE,       -- 用户 ID
                                                       dialect_type TEXT    NOT NULL DEFAULT '普通话', -- 方言类型
                                                       created_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                       updated_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_dialect_preference_user_id
    ON user_dialect_preference(user_id);