package com.llm.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    // 知识库存储
    private final List<RagDocument> knowledgeBase = new ArrayList<>();

    // 倒排索引：关键词 → 文档列表
    private final Map<String, List<RagDocument>> invertedIndex = new HashMap<>();

    // RAG 开关（可配置）
    private boolean enabled = true;

    @PostConstruct
    public void init() {
        loadKnowledgeBase();
        buildInvertedIndex();
        log.info("✅ RAG 知识库加载完成，共 {} 条文档", knowledgeBase.size());
        log.info("📇 倒排索引构建完成，共 {} 个关键词", invertedIndex.size());
        log.info("🔘 RAG 当前状态: {}", enabled ? "开启" : "关闭");
    }

    // ==================== 知识库加载 ====================

    private void loadKnowledgeBase() {
        // 方式一：从 resources 加载文件
        loadFromResources();

        // 方式二：硬编码示例数据（作为备选）
        if (knowledgeBase.isEmpty()) {
            loadSampleData();
        }
        log.info("📚 知识库已加载 {} 条文档", knowledgeBase.size());
    }

    /**
     * 从 resources/rag-knowledge.txt 加载知识库
     */
    private void loadFromResources() {
        try {
            InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("rag-knowledge.txt");
            if (is == null) {
                log.warn("⚠️ 未找到 rag-knowledge.txt 文件，使用默认示例数据");
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue; // 跳过空行和注释
                    }
                    // 格式：标题 || 内容
                    String[] parts = line.split("\\|\\|");
                    if (parts.length >= 2) {
                        String title = parts[0].trim();
                        String content = parts[1].trim();
                        knowledgeBase.add(new RagDocument(title, content));
                    }
                }
            }
            log.info("✅ 从文件加载 {} 条知识", knowledgeBase.size());
        } catch (Exception e) {
            log.warn("⚠️ 加载知识库文件失败: {}", e.getMessage());
        }
    }

    /**
     * 硬编码示例数据
     */
    private void loadSampleData() {
        knowledgeBase.add(new RagDocument(
                "年假政策",
                "公司年假政策：员工入职满1年后可享受5天带薪年假，满3年可享受10天，满5年可享受15天。年假需提前3个工作日申请。"
        ));
        knowledgeBase.add(new RagDocument(
                "考勤规则",
                "考勤规则：工作日上班时间为9:00-18:00，午休12:00-13:00。迟到超过30分钟算半天旷工，超过2小时算一天旷工。"
        ));
        knowledgeBase.add(new RagDocument(
                "福利制度",
                "公司福利：五险一金、补充商业保险、年度体检、节日礼品、团队聚餐、员工培训补贴。"
        ));
        knowledgeBase.add(new RagDocument(
                "请假流程",
                "请假流程：事假需提前1天申请，病假需提供医院证明，婚假为3天，产假为98天。所有请假需通过系统提交审批。"
        ));
        knowledgeBase.add(new RagDocument(
                "加班规定",
                "加班规定：工作日加班按1.5倍工资计算，周末加班按2倍工资计算，法定节假日加班按3倍工资计算。加班需提前申请审批。"
        ));
        knowledgeBase.add(new RagDocument(
                "报销流程",
                "报销流程：差旅费、办公用品费需在发生后5个工作日内提交报销申请，需附发票和审批单。报销金额超过1000元需部门经理审批。"
        ));
        log.info("📚 已加载 {} 条示例数据", knowledgeBase.size());
    }

    // ==================== 倒排索引构建 ====================

    private void buildInvertedIndex() {
        // 停用词
        Set<String> stopWords = Set.of(
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都",
                "一", "个", "上", "也", "很", "到", "说", "要", "去", "你", "会",
                "着", "没有", "看", "好", "自己", "这", "那", "它", "他", "她", "们"
        );

        for (RagDocument doc : knowledgeBase) {
            // 提取关键词
            String text = doc.getContent() + " " + doc.getTitle();
            String clean = text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ");
            String[] words = Arrays.stream(clean.split("\\s+"))
                    .filter(w -> w.length() >= 2)
                    .filter(w -> !stopWords.contains(w))
                    .map(String::toLowerCase)
                    .toArray(String[]::new);

            // 建立索引
            for (String word : words) {
                invertedIndex.computeIfAbsent(word, k -> new ArrayList<>()).add(doc);
            }
        }
        log.info("📇 倒排索引构建完成，共 {} 个关键词", invertedIndex.size());
    }

    // ==================== 检索方法 ====================

    /**
     * 检索知识库
     * @param query 用户查询
     * @return 匹配的文档内容，没有匹配返回 null
     */
    public String search(String query) {
        if (!enabled) {
            log.info("🔘 RAG 已关闭，跳过检索");
            return null;
        }

        if (query == null || query.isEmpty()) {
            return null;
        }

        // 1. 提取查询关键词
        String clean = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ");
        String[] queryWords = clean.split("\\s+");

        // 2. 计算每个文档的匹配分数
        Map<RagDocument, Integer> scoreMap = new HashMap<>();
        for (String word : queryWords) {
            if (word.length() < 2) continue;
            List<RagDocument> docs = invertedIndex.get(word.toLowerCase());
            if (docs != null) {
                for (RagDocument doc : docs) {
                    scoreMap.put(doc, scoreMap.getOrDefault(doc, 0) + 1);
                }
            }
        }

        if (scoreMap.isEmpty()) {
            log.info("📋 [RAG] 未找到匹配的知识");
            return null;
        }

        // 3. 按分数排序，取前 3 个
        List<Map.Entry<RagDocument, Integer>> sorted = scoreMap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3)
                .collect(Collectors.toList());

        // 4. 构建返回结果
        StringBuilder result = new StringBuilder();
        for (Map.Entry<RagDocument, Integer> entry : sorted) {
            RagDocument doc = entry.getKey();
            result.append("📄 【").append(doc.getTitle()).append("】\n");
            result.append(doc.getContent()).append("\n\n");
            log.debug("[RAG] 匹配: {} (score={})", doc.getTitle(), entry.getValue());
        }

        log.info("📋 [RAG] 检索到 {} 条匹配结果", sorted.size());
        return result.toString();
    }

    // ==================== 开关控制（用于对比测试） ====================

    public void enable() {
        this.enabled = true;
        log.info("🔘 RAG 已开启");
    }

    public void disable() {
        this.enabled = false;
        log.info("🔘 RAG 已关闭");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("🔘 RAG 状态已切换为: {}", enabled ? "开启" : "关闭");
    }

    // ==================== 工具方法 ====================

    public List<RagDocument> getAllDocuments() {
        return knowledgeBase;
    }

    public int getDocumentCount() {
        return knowledgeBase.size();
    }

    // ==================== 内部类 ====================

    public static class RagDocument {
        private final String title;
        private final String content;

        public RagDocument(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }
    }
}