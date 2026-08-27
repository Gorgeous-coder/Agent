package com.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库初始化器。
 *
 * <p>Spring Boot 4.x 的 DataSourceScriptDatabaseInitializer 对 SQLite 兼容性不佳
 * （多行注释、CHECK 约束等容易解析失败），这里手动读取 schema.sql 并逐条执行。</p>
 *
 * <p>所有建表语句都使用 CREATE TABLE IF NOT EXISTS，重复执行不会报错。</p>
 */
@Slf4j
@Component
public class SqliteSchemaInitializer {

    private static final String SCHEMA_PATH = "sqlite/schema.sql";

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private final JdbcTemplate jdbcTemplate;

    public SqliteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            ensureDbDirectoryExists();
            String sql = readSchemaFile();
            List<String> statements = splitStatements(sql);

            jdbcTemplate.execute((Connection connection) -> {
                try (Statement stmt = connection.createStatement()) {
                    for (String statement : statements) {
                        if (statement.isBlank()) continue;
                        stmt.execute(statement);
                    }
                    return null;
                }
            });

            log.info("[SqliteSchema] 数据库 Schema 初始化完成, 执行语句数: {}", statements.size());
        } catch (Exception e) {
            log.error("[SqliteSchema] 数据库 Schema 初始化失败: {}", e.getMessage(), e);
            throw new IllegalStateException("数据库 Schema 初始化失败", e);
        }
    }

    /**
     * 确保 SQLite 数据库文件所在目录存在。
     * SQLite 只会自动创建 db 文件，不会自动创建父目录，目录不存在会报 SQLITE_CANTOPEN。
     */
    private void ensureDbDirectoryExists() {
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:sqlite:")) {
            return;
        }
        String dbPath = datasourceUrl.substring("jdbc:sqlite:".length());
        // 去掉 URL 参数（如果有的话）
        int queryIdx = dbPath.indexOf('?');
        if (queryIdx > 0) {
            dbPath = dbPath.substring(0, queryIdx);
        }
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                log.info("[SqliteSchema] 数据库目录已创建: {}", parentDir.getAbsolutePath());
            } else {
                log.warn("[SqliteSchema] 数据库目录创建失败: {}", parentDir.getAbsolutePath());
            }
        }
    }

    private String readSchemaFile() throws Exception {
        ClassPathResource resource = new ClassPathResource(SCHEMA_PATH);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 将 SQL 脚本按分号拆分为独立语句，同时正确处理字符串和注释。
     */
    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;

            // 行注释
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            // 块注释
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++; // 跳过 '/'
                }
                continue;
            }

            // 检测注释开头
            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '-' && next == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }

            // 字符串处理
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            // 语句分隔
            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        // 最后一条可能没有分号
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }

        return statements;
    }
}
