package com.github.wechat.ilink.sdk.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简关键词检索 RAG 模块（业务层，非 SDK 源码）。
 *
 * <p>原理：读取本地 {@code rag-docs/} 目录下所有 .txt 文件，按空行/句号切成片段；
 * 用户提问时做极简分词（中文 2 字窗口 + 英文单词），统计每个片段命中的关键词数，
 * 返回命中数最多的前 {@code topK} 个片段，作为"参考资料"拼入 Prompt 交给大模型。
 *
 * <p>布尔开关 {@code enableRag}：
 * <ul>
 *   <li>{@code true}  — 开启检索增强，命中片段时把上下文拼入 Prompt；</li>
 *   <li>{@code false} — 完全不检索（{@link #search(String)} 恒返回 null），走 LLM 闲聊兜底。</li>
 * </ul>
 * 方便做对比测试：同样的问题，开启/关闭 RAG 各问一次看回答差异。
 */
public class LocalRag {

    private final List<String> chunks = new ArrayList<>();
    private volatile boolean enabled;
    private final int topK;
    private final int maxChars;

    /** 中文连续片段（含数字，如 "第26组"） */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5\\d]{2,}");
    /** 英文单词 */
    private static final Pattern EN_WORD = Pattern.compile("[a-zA-Z]{2,}");

    /**
     * @param docDir  文档目录（相对路径基于运行目录，也可传绝对路径）
     * @param enabled 是否开启检索（enableRag 开关）
     * @param topK    最多返回几个片段
     */
    public LocalRag(String docDir, boolean enabled, int topK) {
        this.enabled = enabled;
        this.topK = Math.max(1, topK);
        this.maxChars = 800;
        load(docDir);
    }

    /** 读取目录下所有 .txt，切段 */
    private void load(String docDir) {
        if (docDir == null || docDir.trim().isEmpty()) return;
        Path dir = Paths.get(docDir.trim());
        if (!Files.isDirectory(dir)) {
            System.err.println("[RAG] 文档目录不存在，RAG 不可用: " + dir.toAbsolutePath());
            return;
        }
        int files = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : stream) {
                files++;
                splitFile(p);
            }
        } catch (IOException e) {
            System.err.println("[RAG] 读取文档目录失败: " + e.getMessage());
        }
        System.out.println("[RAG] 已加载 " + files + " 个文档，共 " + chunks.size() + " 个片段"
                + (enabled ? "（检索已开启）" : "（检索已关闭）"));
    }

    private void splitFile(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // 按空行或句号/问号/感叹号切段
            String[] parts = content.split("\\s*[\\r\\n]+\\s*|(?<=[。！？!?])");
            for (String part : parts) {
                String p = part.trim();
                if (p.length() >= 4) {  // 太短的片段没有检索价值
                    chunks.add(p);
                }
            }
        } catch (IOException e) {
            System.err.println("[RAG] 读取文档失败: " + file + " - " + e.getMessage());
        }
    }

    /**
     * 关键词检索：返回拼接后的上下文片段；未命中或未开启返回 null。
     *
     * @param query 用户问题
     * @return 命中的文档片段拼接文本（最多 topK 段、maxChars 字符），或 null
     */
    public String search(String query) {
        if (!enabled) return null;
        if (chunks.isEmpty()) return null;
        if (query == null || query.trim().isEmpty()) return null;

        List<String> keywords = keywords(query);
        if (keywords.isEmpty()) return null;

        // 统计每个片段命中的关键词数（同一片段内一个词只算一次）
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int score = 0;
            for (String kw : keywords) {
                if (chunk.contains(kw)) score++;
            }
            if (score > 0) {
                hits.add(new Hit(i, chunk, score));
            }
        }
        if (hits.isEmpty()) return null;

        // 按命中数降序，取 topK
        hits.sort((a, b) -> Integer.compare(b.score, a.score));
        StringBuilder sb = new StringBuilder();
        int len = 0;
        int count = 0;
        for (Hit h : hits) {
            if (count >= topK) break;
            if (len + h.chunk.length() > maxChars) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(h.chunk);
            len += h.chunk.length();
            count++;
        }
        return sb.toString();
    }

    /**
     * 极简分词：中文连续串按 2 字窗口切（兼容"第26组"这种带数字的），整串≤6字也加入；
     * 英文提取 2 字母以上单词（小写）。
     */
    private List<String> keywords(String text) {
        Set<String> kws = new LinkedHashSet<>();
        Matcher m = CJK_RUN.matcher(text);
        while (m.find()) {
            String s = m.group();
            for (int i = 0; i + 2 <= s.length(); i++) {
                kws.add(s.substring(i, i + 2));
            }
            if (s.length() <= 6) kws.add(s);
        }
        Matcher m2 = EN_WORD.matcher(text);
        while (m2.find()) {
            kws.add(m2.group().toLowerCase());
        }
        return new ArrayList<>(kws);
    }

    /** 是否开启检索 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 动态开关（对比测试时可在运行中切换） */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        System.out.println("[RAG] 检索已" + (enabled ? "开启" : "关闭"));
    }

    private static class Hit {
        final int index;
        final String chunk;
        final int score;

        Hit(int index, String chunk, int score) {
            this.index = index;
            this.chunk = chunk;
            this.score = score;
        }
    }
}
