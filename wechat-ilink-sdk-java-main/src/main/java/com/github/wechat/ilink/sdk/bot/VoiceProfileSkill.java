package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音音色偏好管理 Skill（业务层，非 SDK 源码）。
 *
 * <p>支持三种 CosyVoice 系统音色：
 * <ul>
 *   <li>longanyang 龙安洋（默认，成熟稳重男声）</li>
 *   <li>longxiaochun 龙小淳（温柔女声）</li>
 *   <li>longchen 龙成（年轻活力男声）</li>
 * </ul>
 *
 * <p>触发词示例：
 * <ul>
 *   <li>"音色列表" / "有哪些音色" / "音色有哪些" → 列出可用音色</li>
 *   <li>"当前音色" / "我在用什么音色" → 查询当前默认</li>
 *   <li>"切换音色 龙小淳" / "换音色 小淳" / "用龙小淳" → 切换默认音色</li>
 * </ul>
 *
 * <p>持久化到项目根目录下的 {@code voice_profile.json}，BotMain 启动时读取该文件作为
 * 默认 voice 覆盖 {@code ai-bot.properties} 中的设置。运行中切换后需重启 BotMain 生效。
 */
public class VoiceProfileSkill implements Skill {

    /** 全部可用音色（id → 中文名 + 简介） */
    private static final LinkedHashMap<String, String> VOICES = new LinkedHashMap<>();
    static {
        VOICES.put("longanyang", "龙安洋（默认，成熟稳重男声）");
        VOICES.put("longxiaochun", "龙小淳（温柔女声）");
        VOICES.put("longchen", "龙成（年轻活力男声）");
    }

    /** 音色别名 → 标准化 id */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        ALIAS.put("龙安洋", "longanyang");
        ALIAS.put("安洋", "longanyang");
        ALIAS.put("龙小淳", "longxiaochun");
        ALIAS.put("小淳", "longxiaochun");
        ALIAS.put("龙成", "longchen");
        ALIAS.put("小成", "longchen");
        ALIAS.put("longanyang", "longanyang");
        ALIAS.put("longxiaochun", "longxiaochun");
        ALIAS.put("longchen", "longchen");
    }

    /** 触发词：含"音色"/"声音"/"嗓音"等 */
    private static final String INTENT_KEYWORDS = "音色|声音|嗓音";

    private final String profilePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private String currentVoice = "longanyang";

    /**
     * BotMain 启动时调用，加载持久化的默认 voice。
     * @return 保存的 voice id；如果文件不存在/解析失败/没保存过，返回空字符串（不是 longanyang，避免误覆盖配置）
     */
    public static String loadDefaultVoice(String profilePath) {
        try {
            Path p = Paths.get(profilePath);
            if (!Files.exists(p)) return "";
            ObjectMapper m = new ObjectMapper();
            JsonNode root = m.readTree(p.toFile());
            String v = root.path("defaultVoice").asText("");
            if (!v.isEmpty() && VOICES.containsKey(v)) return v;
        } catch (Exception ignore) { }
        return "";
    }

    /** 构造函数：profilePath 是 voice_profile.json 的路径（可相对） */
    public VoiceProfileSkill(String profilePath) {
        this.profilePath = profilePath == null || profilePath.trim().isEmpty()
                ? "voice_profile.json" : profilePath.trim();
        // 启动时加载持久化的默认 voice；如果文件不存在或没保存，currentVoice 兜底为 longanyang
        String saved = loadDefaultVoice(this.profilePath);
        this.currentVoice = saved.isEmpty() ? "longanyang" : saved;
    }

    @Override
    public String name() {
        return "音色管理";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!text.contains("音色") && !text.contains("声音") && !text.contains("嗓音")) {
            // 也接受"换嗓音"/"切声音"
            if (!(text.startsWith("切") || text.startsWith("换") || text.startsWith("用"))) {
                return null;
            }
        }

        // 列出
        if (text.contains("列表") || text.contains("哪些") || text.contains("有几个") || text.contains("有什么")) {
            StringBuilder sb = new StringBuilder("🎙 可用音色列表：\n");
            for (Map.Entry<String, String> e : VOICES.entrySet()) {
                String marker = e.getKey().equals(currentVoice) ? "  ← 当前默认" : "";
                sb.append("  • ").append(e.getValue()).append(marker).append("\n");
            }
            sb.append("\n💡 说'切换音色 龙小淳'可切换默认音色（重启 BotMain 生效）");
            return sb.toString().trim();
        }

        // 查询当前
        if (text.contains("当前") || text.contains("我在用") || text.contains("正在用") || text.contains("现在用")) {
            String desc = VOICES.getOrDefault(currentVoice, currentVoice);
            return "🎙 当前默认音色：" + desc;
        }

        // 切换（含"切换"/"换"/"用" + 音色别名）
        for (Map.Entry<String, String> e : ALIAS.entrySet()) {
            String name = e.getKey();
            String id = e.getValue();
            if (text.contains(name) && (text.contains("切换") || text.contains("换")
                    || text.contains("用") || text.startsWith("切") || text.startsWith("换") || text.startsWith("用"))) {
                return setDefault(id);
            }
        }
        return null;
    }

    private String setDefault(String voiceId) {
        if (!VOICES.containsKey(voiceId)) return "⚠️ 未知音色：" + voiceId;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("defaultVoice", voiceId);
            root.put("updatedAt", System.currentTimeMillis());
            Files.write(Paths.get(profilePath),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            currentVoice = voiceId;
            return "✅ 已将默认音色切换为：" + VOICES.get(voiceId)
                    + "\n💡 重启 BotMain 后 TTS 语音回复会用新音色（保持单音色进程内一致）";
        } catch (IOException e) {
            return "⚠️ 保存音色配置失败：" + e.getMessage();
        }
    }

    /** 暴露给 BotMain 启动时读 */
    public String getCurrentVoice() {
        return currentVoice;
    }

    /**
     * 把音色名称/别名解析成标准 voice id（供"用X音色说YY"指令用）。
     *
     * @param name 用户说的音色名（如 "龙小淳" / "小淳" / "longxiaochun"）
     * @return 标准 voice id；不认识返回 null
     */
    public static String resolveVoiceId(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        String hit = ALIAS.get(trimmed);
        if (hit != null) return hit;
        // 模糊匹配：名字包含关键词
        for (Map.Entry<String, String> e : ALIAS.entrySet()) {
            if (trimmed.contains(e.getKey()) || e.getKey().contains(trimmed)) {
                return e.getValue();
            }
        }
        return null;
    }
}