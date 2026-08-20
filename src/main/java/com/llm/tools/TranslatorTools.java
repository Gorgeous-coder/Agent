package com.llm.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TranslatorTools implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_URL = "https://api.mymemory.translated.net/get";

    /** 翻译意图关键词 */
    private static final Pattern INTENT_PATTERN = Pattern.compile(
            "翻译|译成|译一下|怎么说|怎么讲|怎么读|翻译成");

    /** 中文/英文 → 语言代码（MyMemory 用 ISO 639-1 简码） */
    private static final Map<String, String> LANG_CODE = new HashMap<>();

    static {
        LANG_CODE.put("中文", "zh");
        LANG_CODE.put("汉语", "zh");
        LANG_CODE.put("英文", "en");
        LANG_CODE.put("英语", "en");
        LANG_CODE.put("日语", "ja");
        LANG_CODE.put("日文", "ja");
        LANG_CODE.put("韩语", "ko");
        LANG_CODE.put("韩文", "ko");
        LANG_CODE.put("法语", "fr");
        LANG_CODE.put("德语", "de");
        LANG_CODE.put("西班牙语", "es");
        LANG_CODE.put("俄语", "ru");
        LANG_CODE.put("泰语", "th");
        LANG_CODE.put("意大利语", "it");
        LANG_CODE.put("葡萄牙语", "pt");
        LANG_CODE.put("阿拉伯语", "ar");
        LANG_CODE.put("越南语", "vi");
    }

    /** 提取原文：把xxx翻译成yyy / 翻译xxx / xxx怎么说 / xxx翻译 */
    private static final Pattern SRC_BEFORE =
            Pattern.compile("(?:把|将)(.+?)(?:翻译|译成|翻译成)");
    /** 注意"翻译成/译成"必须排在"翻译"前面，避免把"成"字残留进原文 */
    private static final Pattern SRC_AFTER =
            Pattern.compile("(?:翻译成|译成|翻译一下|译一下|翻译)(.+)$");
    private static final Pattern SRC_QUESTION =
            Pattern.compile("^(.+?)(?:用(?:中文|汉语|英文|英语|日语|日文|韩语|韩文|法语|德语|西班牙语|俄语|泰语|意大利语|葡萄牙语|阿拉伯语))?(?:怎么(?:说|讲|读))");
    private static final Pattern SRC_SUFFIX =
            Pattern.compile("^(.{1,50}?)(?:翻译|译一下|翻译一下)$");

    private final OkHttpClient httpClient;

    public TranslatorTools() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 一站式处理：判断文本是否翻译意图，是则翻译并返回结果，否则返回 null。
     *
     * @param text 用户原始消息
     * @return 翻译结果文本（原文/译文/语言方向），或 null（非翻译意图）
     */
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!INTENT_PATTERN.matcher(text).find()) return null;

        String source = extractSource(text);
        if (source == null || source.isEmpty()) return null;

        String targetLang = detectTargetLang(text, source);
        String sourceLang = containsChinese(source) ? "zh" : "en";

        try {
            String translated = translate(source, sourceLang, targetLang);
            String langDir = langName(sourceLang) + " → " + langName(targetLang);
            return "🔤 原文：" + source + "\n🌐 译文：" + translated + "\n（" + langDir + "）";
        } catch (Exception e) {
            log.error("翻译失败: {}", e.getMessage());
            return "抱歉，翻译失败：" + e.getMessage();
        }
    }

    /**
     * AI 工具调用入口
     */
    @Tool(description = "翻译文本。当用户要求翻译、译成某种语言、问某句话怎么说时调用此工具。")
    public String translate(
            @ToolParam(description = "要翻译的原文或包含翻译指令的完整消息") String text) {
        log.info("[TranslatorTools] 被调用: text={}", text);
        return tryHandle(text);
    }

    /** 是否翻译意图（外部可用于提前过滤） */
    public boolean isTranslateQuery(String text) {
        return text != null && !text.isEmpty() && INTENT_PATTERN.matcher(text).find();
    }

    /** 从消息中提取要翻译的原文；提取不到返回 null */
    public String extractSource(String text) {
        if (text == null) return null;
        // 1) 把xxx翻译成yyy / 将xxx翻译成yyy
        Matcher m = SRC_BEFORE.matcher(text);
        if (m.find()) return clean(m.group(1));
        // 2) 翻译xxx / 翻译一下xxx / 翻译成yyy的xxx（取"翻译"后内容）
        m = SRC_AFTER.matcher(text);
        if (m.find()) return clean(m.group(1));
        // 3) xxx怎么说 / xxx用英文怎么说
        m = SRC_QUESTION.matcher(text);
        if (m.find()) return clean(m.group(1));
        // 4) xxx翻译（翻译在末尾）
        m = SRC_SUFFIX.matcher(text);
        if (m.find()) return clean(m.group(1));
        return null;
    }

    /** 去掉提取结果里的语言词/连接词 */
    private static String clean(String s) {
        if (s == null) return "";
        return s
                .replaceAll("(帮我|请|把|将|一下|翻译成|译成|翻译|译一下|怎么说|怎么讲|怎么读|用)", "")
                .replaceAll("(中文|汉语|英文|英语|日语|日文|韩语|韩文|法语|德语|西班牙语|俄语|泰语|意大利语|葡萄牙语|阿拉伯语)", "")
                .trim();
    }

    /** 检测目标语言：消息里显式指定（如"翻译成英文"）优先，否则按原文自动 */
    private static String detectTargetLang(String text, String source) {
        for (Map.Entry<String, String> e : LANG_CODE.entrySet()) {
            if (text.contains(e.getKey())) return e.getValue();
        }
        return containsChinese(source) ? "en" : "zh";
    }

    private static boolean containsChinese(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fa5') return true;
        }
        return false;
    }

    private static String langName(String code) {
        if ("zh".equals(code)) return "中文";
        if ("en".equals(code)) return "英文";
        for (Map.Entry<String, String> e : LANG_CODE.entrySet()) {
            if (e.getValue().equals(code)) return e.getKey();
        }
        return code;
    }

    /** 调用 MyMemory 翻译接口，返回译文 */
    private String translate(String q, String src, String tgt) throws IOException {
        if (q.length() > 500) {
            throw new IOException("单次最多翻译 500 字符，当前 " + q.length() + " 字符");
        }
        String url = API_URL + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
                + "&langpair=" + src + "|" + tgt;
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "wechat-ilink-bot/1.0")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || body == null || body.trim().isEmpty()) {
                throw new IOException("翻译服务请求失败: HTTP " + response.code());
            }
            JsonNode root = MAPPER.readTree(body);
            int status = root.path("responseStatus").asInt(-1);
            if (status != 200) {
                throw new IOException(root.path("responseDetails").asText("未知错误"));
            }
            String translated = root.path("responseData").path("translatedText").asText("");
            if (translated == null || translated.trim().isEmpty()) {
                throw new IOException("翻译结果为空");
            }
            return translated.trim();
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}