package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户作息设置 Skill（业务层，非 SDK 源码）。
 *
 * <p>记录用户的日常作息（起床 / 上班 / 下班时间），持久化到 {@code routine_profile.json}，
 * 供其他 Skill（早安问候、工作日健康提醒、喝水建议等）按作息安排内容。
 *
 * <p>触发词示例：
 * <ul>
 *   <li>"我早上7点起床" / "记录作息 7点起床" → 设置起床时间</li>
 *   <li>"我9点上班，6点下班" → 设置上班/下班</li>
 *   <li>"我的作息" / "作息安排" → 查询已存作息</li>
 * </ul>
 */
public class RoutineSkill implements Skill {

    private static final Pattern SET_PATTERN = Pattern.compile("(起床|上班|下班)");
    private static final Pattern QUERY_PATTERN = Pattern.compile("我的作息|作息安排|作息是啥|作息信息");

    private final String profilePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public RoutineSkill(String profilePath) {
        this.profilePath = profilePath == null || profilePath.trim().isEmpty()
                ? "routine_profile.json" : profilePath.trim();
    }

    @Override
    public String name() {
        return "作息设置";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!text.contains("作息") && !(text.contains("起床") || text.contains("上班") || text.contains("下班"))) {
            return null;
        }
        if (QUERY_PATTERN.matcher(text).find()) {
            return query();
        }
        if (SET_PATTERN.matcher(text).find()) {
            return set(text);
        }
        return null;
    }

    private String set(String text) {
        // 提取起床/上班/下班时间（用标点+连接词分段）
        // 注意：作息字段只支持一个 wakeUp / workStart / workEnd，多个时段只取第一个出现的
        // （如"我早上7点上班，下午2点上班"只记 7 点；中饭时间不在字段内会忽略）
        String wake = null, workStart = null, workEnd = null;
        String[] parts = text.split("[,，。；;、]|然后|和|跟");
        for (String p : parts) {
            if (p.contains("起床") && wake == null) {
                wake = ScheduleUtil.parseClockTime(p);
            }
            if (p.contains("上班") && workStart == null) {
                workStart = ScheduleUtil.parseClockTime(p);
            }
            if (p.contains("下班") && workEnd == null) {
                workEnd = ScheduleUtil.parseClockTime(p);
            }
        }
        if (wake == null && workStart == null && workEnd == null) {
            return "⚠️ 没识别出时间，试试：'我早上7点起床，9点上班，18点下班'";
        }
        // 保存
        ObjectNode root = load();
        ObjectNode u;
        JsonNode def = root.path("default");
        if (def instanceof ObjectNode) {
            u = (ObjectNode) def;
        } else {
            u = root.putObject("default");
        }
        if (wake != null) u.put("wakeUp", wake);
        if (workStart != null) u.put("workStart", workStart);
        if (workEnd != null) u.put("workEnd", workEnd);
        save(root);

        StringBuilder sb = new StringBuilder("✅ 作息已记录：\n");
        if (wake != null) sb.append("  🌅 起床 ").append(wake).append("\n");
        if (workStart != null) sb.append("  💼 上班 ").append(workStart).append("\n");
        if (workEnd != null) sb.append("  🏠 下班 ").append(workEnd).append("\n");
        sb.append("💡 说'我的作息'可随时查看");
        return sb.toString().trim();
    }

    private String query() {
        ObjectNode root = load();
        JsonNode u = root.path("default");
        if (u.isMissingNode() || (u.path("wakeUp").isMissingNode()
                && u.path("workStart").isMissingNode() && u.path("workEnd").isMissingNode())) {
            return "💤 还没有记录作息。试试：'我早上7点起床，9点上班，18点下班'";
        }
        StringBuilder sb = new StringBuilder("📋 我的作息：\n");
        if (!u.path("wakeUp").isMissingNode()) sb.append("  🌅 起床 ").append(u.path("wakeUp").asText()).append("\n");
        if (!u.path("workStart").isMissingNode()) sb.append("  💼 上班 ").append(u.path("workStart").asText()).append("\n");
        if (!u.path("workEnd").isMissingNode()) sb.append("  🏠 下班 ").append(u.path("workEnd").asText()).append("\n");
        return sb.toString().trim();
    }

    private ObjectNode load() {
        try {
            if (Files.exists(Paths.get(profilePath))) {
                return (ObjectNode) mapper.readTree(Paths.get(profilePath).toFile());
            }
        } catch (IOException ignore) { }
        return mapper.createObjectNode();
    }

    private void save(ObjectNode root) {
        try {
            Files.write(Paths.get(profilePath),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Routine] 保存失败: " + e.getMessage());
        }
    }
}
