package com.github.wechat.ilink.sdk.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 多工具协同规划器（AgentPlanner，业务层，非 SDK 源码）。
 *
 * <p>把一条自然语言提问自主拆解成多个子任务，按顺序调用多个不同 Skill 工具，
 * 上一个 Skill 的结果进入共享上下文，供后续任务参考；全部执行完由大模型整合输出
 * 一份完整通顺的综合回答。单个工具失败自动跳过，不整体崩溃。
 *
 * <p>场景示例："我明天要去南京玩"
 * <ol>
 *   <li>拆解：南京 + 明天 + 旅行 → 需要天气/穿搭/美食/行程</li>
 *   <li>任务管线：天气 → 穿衣建议 → 南京美食 → 行程整合</li>
 *   <li>LLM 把四部分结果整合成一份"天气 + 穿衣 + 美食 + 一日游行程"的综合回答</li>
 * </ol>
 *
 * <p>只处理多领域组合（天气/美食/行程 ≥2 个领域）；单意图返回 null，走原有单工具路由。
 */
public class AgentPlanner {

    /** 天气意图词 */
    private static final Pattern P_WEATHER = Pattern.compile(
            "天气|气温|温度|冷不冷|热不热|下雨|下雪|带伞|穿什么|穿啥|出门|穿衣");
    /** 美食意图词 */
    private static final Pattern P_FOOD = Pattern.compile(
            "吃|美食|餐厅|饭馆|馆子|好吃|饭菜|特色菜|小吃|夜宵");
    /** 行程/旅行意图词（触发时默认拆解出天气+美食+行程完整管线）。
     *  ⚠️ 不含"攻略"："XX攻略"是单意图（直接搜小红书），不该被 Agent 拆成天气+美食。 */
    private static final Pattern P_TRAVEL = Pattern.compile(
            "去|玩|旅游|旅行|行程|逛|出发|一日游|度假|出差|游玩");

    private final Weather weather;
    private final WeatherBriefingSkill weatherBriefing;
    private final DiningSkill dining;
    private final XhsCollector xhs;
    private final AiClient aiClient;
    private final String defaultCity;
    private final String systemPrompt;

    public AgentPlanner(Weather weather, WeatherBriefingSkill weatherBriefing,
                        DiningSkill dining, XhsCollector xhs, AiClient aiClient,
                        String defaultCity, String systemPrompt) {
        this.weather = weather;
        this.weatherBriefing = weatherBriefing;
        this.dining = dining;
        this.xhs = xhs;
        this.aiClient = aiClient;
        this.defaultCity = defaultCity;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 尝试多工具协同规划。命中（≥2 个领域）返回综合回答；单意图返回 null（走原路由）。
     */
    public String tryPlan(String text) {
        if (text == null || text.isEmpty()) return null;

        String city = WeatherBriefingSkill.findKnownCity(text);
        // ⚠️ 没识别出真实城市 → 不跑小红书（避免拿全局默认城市去搜，返回一堆无关链接）
        boolean hasRealCity = city != null;
        if (city == null) city = defaultCity;

        boolean hasWeather = P_WEATHER.matcher(text).find();
        boolean hasFood = P_FOOD.matcher(text).find();
        boolean hasTravel = P_TRAVEL.matcher(text).find();

        // 去某地玩/旅行 → 默认需要天气 + 穿搭 + 美食 + 行程（Agent 自主拆解）
        if (hasTravel) {
            hasWeather = true;
            hasFood = true;
        }
        // 少于 2 个领域 → 单工具路由处理
        int domains = (hasWeather ? 1 : 0) + (hasFood ? 1 : 0) + (hasTravel ? 1 : 0);
        if (domains < 2) return null;

        System.out.println("[Agent] 多工具协同：" + truncate(text, 40)
                + " | 城市=" + city + (hasRealCity ? "" : "(默认)") + " | 天气=" + hasWeather + " 美食=" + hasFood + " 行程=" + hasTravel);

        // ===== 任务管线（按依赖顺序）=====
        List<Task> tasks = new ArrayList<>();
        if (hasWeather) {
            tasks.add(new Task("weather", "weather", city + " 明天 天气"));
            tasks.add(new Task("dressing", "dressing", city + " 穿什么"));
        }
        if (hasFood) {
            tasks.add(new Task("food", "dining", city + " 有什么好吃的"));
        }
        if (hasTravel) {
            // ⚠️ 小红书内容不在管线里跑 8 条列表（避免 LLM 输出冗长攻略 + 末尾重复）。
            // 改为在 summarize 结果末尾追加 2 条精选链接：①旅游攻略 ②天气穿搭攻略。
            tasks.add(new Task("travel", "travel", city + " 一日游"));
        }

        // ===== 顺序执行 + 结果进上下文 + 单任务容错 =====
        Map<String, String> ctx = new LinkedHashMap<>();
        StringBuilder materials = new StringBuilder();
        for (Task t : tasks) {
            try {
                String result = execute(t.type, t.input);
                if (result != null && !result.isEmpty()) {
                    ctx.put(t.key, result);
                    materials.append("【").append(t.key).append("】\n").append(result).append("\n\n");
                } else {
                    ctx.put(t.key, "(获取失败)");
                    materials.append("【").append(t.key).append("】获取失败，已跳过\n\n");
                }
            } catch (Exception e) {
                // 屏蔽底层异常细节，避免乱码/堆栈进回答
                ctx.put(t.key, "(获取失败)");
                materials.append("【").append(t.key).append("】获取失败，已跳过\n\n");
            }
        }

        // ===== LLM 整合所有结果 =====
        String summary = summarize(text, city, materials.toString(), hasWeather, hasFood, hasTravel);
        // ===== 末尾追加 2 条小红书参考链接（旅游攻略 + 天气穿搭攻略），各 1 条 =====
        if (hasRealCity) {
            String refLinks = buildXhsRefLinks(city);
            if (refLinks != null && !refLinks.isEmpty()) {
                summary = summary + "\n\n" + refLinks;
            }
        }
        return summary;
    }

    /**
     * 实时拉取小红书，追加 2 条参考链接（旅游攻略 + 天气穿搭攻略），各 1 条。
     * 链接带说明文字、实时获取、失败静默跳过（绝不影响主回答，绝无乱码）。
     */
    private String buildXhsRefLinks(String city) {
        if (xhs == null || !xhs.isConfigured()) return "";
        StringBuilder sb = new StringBuilder();
        // ① 目的地旅游攻略（高热度 top1）
        try {
            String travelUrl = xhs.searchTopNote(city + " 旅游攻略");
            if (travelUrl != null && !travelUrl.isEmpty()) {
                sb.append("「参考小红书旅游攻略：").append(travelUrl).append("」\n");
            }
        } catch (Exception e) {
            System.err.println("[Agent] 旅游攻略链接获取失败（已跳过）: " + e.getClass().getSimpleName());
        }
        // ② 结合天气的穿搭攻略（高热度 top1；"穿搭攻略"比"穿搭"精准，命中"夏季穿搭攻略"这类当季帖）
        try {
            String dressUrl = xhs.searchTopNote(city + " 穿搭攻略");
            if (dressUrl != null && !dressUrl.isEmpty()) {
                sb.append("「参考对应天气穿搭攻略：").append(dressUrl).append("」");
            }
        } catch (Exception e) {
            System.err.println("[Agent] 穿搭攻略链接获取失败（已跳过）: " + e.getClass().getSimpleName());
        }
        return sb.toString().trim();
    }

    /** 执行单个子任务 */
    private String execute(String type, String input) {
        switch (type) {
            case "weather":
                return weather.tryHandle(input);
            case "dressing":
                return weatherBriefing.tryHandle(input);
            case "dining":
                return dining.tryHandle(input);
            case "xhs":
                // 小红书采集很慢（30~90s），在独立线程跑并设 110s 上限：
                // 超时就放弃 xhs（返回 null → 上层记"获取失败"跳过），绝不让整个 Agent 管线卡死。
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "xhs-task");
                            t.setDaemon(true);
                            return t;
                        });
                try {
                    java.util.concurrent.Future<String> f =
                            pool.submit(() -> {
                                try {
                                    return xhs.search(input);
                                } catch (Exception e) {
                                    System.err.println("[Agent] 小红书攻略获取失败（已跳过）: " + e.getClass().getSimpleName());
                                    return null;
                                }
                            });
                    try {
                        return f.get(110, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        f.cancel(true);
                        System.err.println("[Agent] 小红书攻略超时（110s），已跳过 xhs 任务");
                        return null;
                    }
                } finally {
                    pool.shutdownNow();
                }
            case "travel":
                return null;  // 行程由 LLM 整合阶段生成
            default:
                return null;
        }
    }

    /** 大模型整合所有子任务结果，输出综合回答；整合失败则直接拼接已收集信息 */
    private String summarize(String question, String city, String materials,
                             boolean hasWeather, boolean hasFood, boolean hasTravel) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(msg("system", systemPrompt));
            }
            messages.add(msg("system",
                    "你是智能出行规划助手。请优先依据【已收集到的信息】回答；信息缺失时如实说明'该部分获取失败'，不要编造。"));
            StringBuilder user = new StringBuilder();
            user.append("【用户问题】").append(question).append("\n\n");
            user.append("【已收集到的信息】\n").append(materials);
            user.append("\n请综合以上信息，输出一份完整通顺的中文回答：\n");
            if (hasTravel) {
                user.append("包含：").append(hasWeather ? "天气简述与穿衣提醒、" : "")
                        .append(hasFood ? "当地美食推荐、" : "")
                        .append(city).append("的简易一日游玩行程安排（2-3 个景点 + 用餐点）。\n");
            } else {
                user.append("将各部分信息自然衔接成一段通顺回答。\n");
            }
            user.append("要求：口语自然、简洁，不要使用 Markdown 表格符号，不要用列表符号过多的排版。");
            messages.add(msg("user", user.toString()));
            return aiClient.chat(messages);
        } catch (Exception e) {
            System.err.println("[Agent] LLM 整合失败，退回拼接输出：" + e.getMessage());
            return "以下为收集到的信息（自动整理失败，已直接输出）：\n\n" + materials.trim();
        }
    }

    private static Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 子任务描述 */
    private static class Task {
        final String key;    // 结果在上下文中的键：weather / dressing / food / travel
        final String type;   // 执行器类型：weather / dressing / dining / travel
        final String input;  // 给执行器的输入文本

        Task(String key, String type, String input) {
            this.key = key;
            this.type = type;
            this.input = input;
        }
    }
}
