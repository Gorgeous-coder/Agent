package com.github.wechat.ilink.sdk.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 iLink AI Bot 主入口（多模态版）- 全面修复版
 */
public class BotMain {

    private final AiClient aiClient;
    private final AiClient visionClient;
    private final DashScopeClient dashScopeClient;
    private final AudioConverter audioConverter;
    private final Weather weather;
    private final WeatherBriefingSkill weatherBriefingSkill;
    private final DiningSkill diningSkill;
    private final AgentPlanner agentPlanner;
    private final Translator translator;
    private final Calculator calculator;
    private final SkillRegistry skillRegistry;
    private final FocusTimerSkill focusTimerSkill;
    private final VoiceProfileSkill voiceProfileSkill;
    private final RoutineSkill routineSkill;
    private final LocalRag localRag;
    private final boolean enableRag;
    /** RSS 订阅推送（半自动化新攻略监控，无 cookie/无风控） */
    private final RssSkill rssSkill;
    /** 小红书实时采集器（Playwright+系统Edge；cookie 配置齐全才启用） */
    private final XhsCollector xhsCollector;
    private final String systemPrompt;
    private final int maxHistoryTurns;
    private final String replyPrefix;
    private final String errorReply;
    private final String unsupportedReply;
    private final boolean imageReplyEnabled;
    private final boolean voiceReplyEnabled;

    /** 活跃用户集合 */
    private final java.util.Set<String> activeUsers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 用户所在城市记忆（userId → 城市名；用户说过"XX天气"后记住，后续"要不要带伞"用这个城市） */
    private final Map<String, String> userCities = new ConcurrentHashMap<>();
    /** 配置里的全局默认城市（bot.default-city） */
    private final String configDefaultCity;

    private final Map<String, List<Map<String, String>>> chatHistories = new ConcurrentHashMap<>();
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-reply");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern DRAW_PATTERN = Pattern.compile(
            "(画|绘制|生成|做|创作|设计).{0,20}(图|图片|照片|壁纸|海报|插画|头像)"
                    + "|(图|图片|照片|壁纸|海报|插画|头像).{0,20}(画|绘制|生成|做|创作|设计)");

    /** 指定音色说文本："用龙小淳说你好" / "用龙成念一首诗" */
    private static final Pattern USE_VOICE_PATTERN = Pattern.compile(
            "用(龙安洋|安洋|龙小淳|小淳|龙成|小成|longanyang|longxiaochun|longchen)"
                    + "(?:的)?(?:音色|声音)?(?:说|读|讲|念)(.+)$");
    /** 三个音色分别说："用三个音色分别说你好" / "用三个音色都说你好" / "用三个音色分别和我说，你好" */
    private static final Pattern MULTI_VOICE_PATTERN = Pattern.compile(
            "用(?:三个|3个|所有|全部)?个?(?:音色|声音)(?:分别|都|各|和)?(?:我|你)?(?:说|读|讲|念)(?:一下|一遍)?[,，、\\s]*(.+)$");

    /** 小红书实时采集触发：含"攻略/穿搭/美食/演唱会/穿什么"等明确咨询词 */
    private static final Pattern XHS_GUIDE_PATTERN = Pattern.compile(
            "攻略|游记|种草|避雷|怎么玩|去哪儿玩|值得去|穿什么|穿搭建议|搭配建议|怎么穿|穿搭|搭配|吃什么|怎么吃|演唱会|美食推荐|美食");
    /**
     * 攻略主题词：从"南京美食攻略"里提取"美食"。
     * ⚠️ 只保留明确攻略意图的词；"景点/酒店/购物/拍照/住宿/路线/行程/打卡"这种
     * 闲聊高频词不触发（用户说"今天去酒店开会""我打卡下班了"不该去爬小红书）。
     */
    private static final Pattern XHS_TOPIC_PATTERN = Pattern.compile(
            "(美食|穿搭|演唱会|旅游|旅行|餐厅|小吃|亲子|滑雪|露营|徒步|避雷|种草|日式|韩系|温柔风|简约风|夏日穿搭|秋冬穿搭)");

    private volatile ILinkClient client;
    private volatile boolean running = true;

    public BotMain(Properties props) {
        String baseUrl = resolve(props, "ai.base-url");
        String apiKey = resolve(props, "ai.api-key");
        String model = resolve(props, "ai.model");
        String visionModel = resolve(props, "ai.vision-model", model);
        double temperature = Double.parseDouble(resolve(props, "ai.temperature", "0.7"));
        int maxTokens = Integer.parseInt(resolve(props, "ai.max-tokens", "2048"));

        this.aiClient = new AiClient(baseUrl, apiKey, model, temperature, maxTokens);
        this.visionClient = new AiClient(baseUrl, apiKey, visionModel, temperature, maxTokens);

        // TTS 默认 voice：先读 voice_profile.json（用户之前切换过），没有就用 ai-bot.properties 的 ai.tts-voice
        String ttsVoiceFromProps = resolve(props, "ai.tts-voice", "longanyang");
        String savedVoice = VoiceProfileSkill.loadDefaultVoice("voice_profile.json");
        String finalVoice = (savedVoice == null || savedVoice.isEmpty()) ? ttsVoiceFromProps : savedVoice;
        this.dashScopeClient = new DashScopeClient(
                apiKey,
                resolve(props, "ai.image-model", "wanx2.1-t2i-turbo"),
                resolve(props, "ai.tts-model", "cosyvoice-v3-flash"),
                finalVoice);
        if (savedVoice != null && !savedVoice.isEmpty()) {
            System.out.println("[TTS] 已从 voice_profile.json 加载默认音色：" + savedVoice);
        }

        this.audioConverter = new AudioConverter(
                resolve(props, "ai.ffmpeg-path", ""),
                resolve(props, "ai.ffprobe-path", ""),
                resolve(props, "ai.rust-silk-path", ""));

        this.systemPrompt = resolve(props, "ai.system-prompt", "");
        this.maxHistoryTurns = Integer.parseInt(resolve(props, "ai.max-history-turns", "20"));
        this.replyPrefix = resolve(props, "bot.reply-prefix", "");
        this.errorReply = resolve(props, "bot.error-reply", "抱歉，处理失败");
        this.unsupportedReply = resolve(props, "bot.unsupported-reply", "暂只支持文本消息");
        this.imageReplyEnabled = Boolean.parseBoolean(resolve(props, "bot.image-reply", "true"));
        this.voiceReplyEnabled = Boolean.parseBoolean(resolve(props, "bot.voice-reply", "true"));
        this.configDefaultCity = resolve(props, "bot.default-city", "深圳");
        this.weather = new Weather(configDefaultCity);

        // ===== 工具类（业务层）=====
        this.translator = new Translator();
        this.calculator = new Calculator();

        // ===== Skill 工具注册表：天气简报优先（"穿/带伞/出门"触发）→ 天气 → 翻译 → 单位换算 → 计算器 =====
        this.skillRegistry = new SkillRegistry();
        // 天气简报排第一：含"出门/穿/带伞"等词时优先于纯天气命中，给出穿衣/带伞建议
        this.weatherBriefingSkill = new WeatherBriefingSkill(weather, configDefaultCity);
        skillRegistry.register(weatherBriefingSkill);
        skillRegistry.register(new Skill() {
            @Override public String name() { return "天气"; }
            @Override public String tryHandle(String text) { return weather.tryHandle(text); }
        });
        skillRegistry.register(new Skill() {
            @Override public String name() { return "翻译"; }
            @Override public String tryHandle(String text) { return translator.tryHandle(text); }
        });
        // 单位换算排在计算器前面：含单位词的算式（如"5千米等于多少米"）先被单位换算命中
        skillRegistry.register(new UnitConverterSkill());
        skillRegistry.register(new Skill() {
            @Override public String name() { return "计算器"; }
            @Override public String tryHandle(String text) { return calculator.tryHandle(text); }
        });
        // 音色管理：放最后，避免误触发（"音色"可能出现在闲聊里）
        this.voiceProfileSkill = new VoiceProfileSkill("voice_profile.json");
        skillRegistry.register(voiceProfileSkill);

        // ===== B 批：作息 / 健康（提醒+日常关怀已删除，用户说太难）=====
        this.routineSkill = new RoutineSkill("routine_profile.json");
        skillRegistry.register(routineSkill);
        skillRegistry.register(new HealthTipSkill());
        skillRegistry.register(new HealthWaterSkill(routineSkill));

        // ===== C 批：美食推荐（dining / daily-dining / meal-plan，用高德 GAODE_KEY）=====
        this.diningSkill = new DiningSkill(System.getenv("GAODE_KEY"), configDefaultCity, weather);
        skillRegistry.register(diningSkill);
        System.out.println("[Skill] 已注册工具: " + skillRegistry.names());

        // 专注计时（带 userId 状态）：不放 SkillRegistry，由路由显式调用（按用户优先级）
        this.focusTimerSkill = new FocusTimerSkill();

        // ===== 小红书实时采集（Playwright+系统Edge，每次独立进程，无 daemon）=====
        String xhsSession = resolve(props, "xhs.cookie.web-session", "").trim();
        String xhsA1 = resolve(props, "xhs.cookie.a1", "").trim();
        String xhsWebId = resolve(props, "xhs.cookie.web-id", "").trim();
        if (!xhsSession.isEmpty() && !xhsA1.isEmpty() && !xhsWebId.isEmpty()) {
            this.xhsCollector = new XhsCollector(xhsSession, xhsA1, xhsWebId,
                    Integer.parseInt(resolve(props, "xhs.top-n", "8")));
            System.out.println("[XhsCollector] 小红书实时采集已启用（发\"XX美食/XX穿搭/XX攻略\"触发）");
        } else {
            this.xhsCollector = null;
            System.out.println("[XhsCollector] 未配置小红书 cookie，实时采集关闭（在 ai-bot.properties 填 xhs.cookie.* 可启用）");
        }

        // ===== 多工具协同规划器（Agent：自主拆解 → 顺序调用多 Skill → LLM 整合）=====
        this.agentPlanner = new AgentPlanner(weather, weatherBriefingSkill, diningSkill,
                xhsCollector, aiClient, configDefaultCity, systemPrompt);
        System.out.println("[Agent] 多工具协同规划器已启动");

        // ===== RAG 关键词检索（enableRag 布尔开关，false 关闭检索做对比测试）=====
        this.enableRag = Boolean.parseBoolean(resolve(props, "bot.enable-rag", "true"));
        this.localRag = new LocalRag(
                resolve(props, "bot.rag-doc-dir", "rag-docs"),
                enableRag,
                Integer.parseInt(resolve(props, "bot.rag-top-k", "3")));

        // ===== RSS 订阅推送（半自动化监控新攻略，无 cookie/无风控；配置见 rss-feeds.properties）=====
        this.rssSkill = new RssSkill(
                resolve(props, "bot.rss-feeds-path", "rss-feeds.properties"),
                payload -> {
                    // payload 格式："userId\n消息"，回调里把消息发给 userId
                    int idx = payload.indexOf('\n');
                    if (idx < 0) return;
                    String uid = payload.substring(0, idx);
                    String msg = payload.substring(idx + 1);
                    try {
                        client.sendText(uid, msg);
                        System.out.println("[RssSkill] 推送 → " + uid + " (" + msg.length() + " 字)");
                    } catch (Exception e) {
                        System.err.println("[RssSkill] 推送失败 " + uid + ": " + e.getMessage());
                    }
                });
        // 注：RssSkill.start() 在 login 成功后调用（等 client 就绪）

        // ===== 启动时检查 FFmpeg =====
        checkFFmpeg();
    }

    // ==================== 主流程 ====================

    public void start() throws Exception {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(false)
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("✅ 扫码登录成功！机器人在线，botId = " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("❌ 登录失败：" + throwable.getMessage());
                        throwable.printStackTrace();
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        handleMessages(messages);
                    }
                })
                .build();

        String qrBase64 = client.executeLogin();
        printQrCode(qrBase64);

        LoginContext ctx = client.getLoginFuture().get();
        System.out.println("登录完成，开始监听消息... botId = " + ctx.getBotId());

        // 启动 RSS 定时推送（每 5 分钟拉一次，发现新条目推送给活跃用户）
        if (rssSkill != null) rssSkill.start();

        while (running) {
            try {
                List<WeixinMessage> msgs = client.getUpdates();
                if (msgs != null && !msgs.isEmpty()) {
                    System.out.println("[INFO] getUpdates() 返回 " + msgs.size() + " 条消息");
                }
            } catch (Exception e) {
                if (!running) break;
                System.err.println("getUpdates 异常，3s 后重试: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("Bot 已停止");
    }

    // ==================== 消息处理（修复版） ====================

    private void handleMessages(List<WeixinMessage> messages) {
        LoginContext ctx = client.getLoginContext();
        String botId = ctx != null ? ctx.getBotId() : null;
        if (messages == null || messages.isEmpty()) return;

        for (WeixinMessage msg : messages) {
            String fromUserId = msg.getFrom_user_id();
            if (fromUserId == null || fromUserId.isEmpty()) continue;
            activeUsers.add(fromUserId);  // 收集活跃用户（定时推送用）
            if (botId != null && botId.equals(fromUserId)) {
                System.out.println("[INFO] 跳过 bot 自己发出的消息");
                continue;
            }

            String contextToken = msg.getContext_token();
            System.out.println("[IN] msg_id=" + msg.getMessage_id()
                    + " from=" + msg.getFrom_user_id()
                    + " ctx=" + truncate(contextToken, 40)
                    + " items=" + (msg.getItem_list() == null ? 0 : msg.getItem_list().size()));

            // ✅ 修复：把完整的 msg 和 contextToken 都传下去
            handleMessage(msg, fromUserId, contextToken);
        }
    }

    /** ✅ 修复：传递 contextToken 给所有子方法 */
    private void handleMessage(WeixinMessage msg, String fromUserId, String contextToken) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        // 收集文字
        StringBuilder textSb = new StringBuilder();
        MessageItem imageItem = null;
        MessageItem voiceItem = null;
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                if (textSb.length() > 0) textSb.append('\n');
                textSb.append(item.getText_item().getText());
            }
            if (item.getImage_item() != null) imageItem = item;
            if (item.getVoice_item() != null) voiceItem = item;
        }
        String text = textSb.toString().trim();

        // ✅ 图片消息：传 contextToken
        if (imageItem != null) {
            System.out.println("收到图片消息｜发送人：" + fromUserId);
            handleImage(fromUserId, imageItem, text, contextToken);
            return;
        }

        // ✅ 语音消息：传 contextToken（已经是）
        if (voiceItem != null) {
            System.out.println("收到语音消息｜发送人：" + fromUserId);
            handleVoice(fromUserId, voiceItem, contextToken);
            return;
        }

        if (!text.isEmpty()) {
            System.out.println("收到文本消息｜发送人：" + fromUserId + " 内容：" + truncate(text, 80));
            handleText(fromUserId, text, contextToken);
        }
    }

    /** ✅ 文本消息：走三级路由（Skill 执行 → RAG 增强 → LLM 闲聊兜底） */
    private void handleText(String fromUserId, String text, String contextToken) {
        replyExecutor.submit(() -> {
            try {
                if (imageReplyEnabled && DRAW_PATTERN.matcher(text).matches()) {
                    System.out.println("检测到画图指令，调用通义万相...");
                    String reply = tryDrawImage(fromUserId, text);
                    if (reply != null) return;
                }

                // ===== 指定音色语音指令：用龙小淳说你好 / 用三个音色分别说你好 =====
                if (tryVoiceCommand(fromUserId, text, contextToken)) return;

                // ===== 多工具协同规划（Agent）优先于小红书单意图 =====
                // "我明天要去南京游玩一天" → 天气+穿搭+美食+行程综合回答；
                // 如果先走小红书触发，会被截胡成"搜南京旅游攻略"，丢了天气查询。
                // tryPlan 只对"≥2 个领域"的多意图返回非 null；单意图（南京美食/苏州天气）返回 null 继续往下走。
                try {
                    String planResult = agentPlanner.tryPlan(text);
                    if (planResult != null) {
                        System.out.println("[路由] 链路0.5-多工具协同：" + truncate(planResult, 60));
                        sendTextReply(fromUserId, planResult, contextToken);
                        return;
                    }
                } catch (Exception e) {
                    // AgentPlanner 内部已容错；这里兜底，异常不阻断后续单意图路由
                    System.err.println("[路由] 多工具协同异常（降级继续单意图）: " + e.getClass().getSimpleName());
                }

                // ===== 小红书实时采集：发"XX美食/XX穿搭/XX攻略" → 实时搜索小红书 =====
                // 触发要求（满足任一）：
                //  ① 命中明确咨询词（攻略/穿搭/美食/演唱会/吃什么等）→ 直接触发（含"哈哈/笑话"的闲聊除外）
                //  ② 带城市 + 含主题/咨询意图词（"苏州有什么好吃的""苏州好玩吗"）
                //     避免"今天去酒店开会""这个景点在哪""我打卡下班了"这类闲聊误触发。
                boolean xhsHit = (XHS_GUIDE_PATTERN.matcher(text).find()
                            && !text.contains("笑话") && !text.contains("哈哈"))
                        || (text.length() >= 4
                            && !text.contains("笑话") && !text.contains("哈哈")
                            && WeatherBriefingSkill.findKnownCity(text) != null
                            && text.matches(".*(美食|好吃|吃的|吃|穿搭|穿|旅游|旅行|好玩|玩|攻略|景点|推荐|什么|想去|要去|演唱会|打卡|种草|避雷|小吃|餐厅|路线|行程).*"));
                if (xhsCollector != null && xhsCollector.isConfigured() && xhsHit) {
                    if (handleXhsGuide(fromUserId, text, contextToken)) return;
                }

                // ===== RSS 订阅监控：用户发消息时加入活跃用户集合，后续有新攻略自动推送 =====
                if (rssSkill != null) rssSkill.addActiveUser(fromUserId);

                // 统一路由：Skill → RAG → LLM 兜底
                String reply = routeMessage(fromUserId, text);
                // ✅ 把触发消息的 contextToken 传给回复，避免用缓存 token
                sendTextReply(fromUserId, reply, contextToken);
            } catch (Exception e) {
                System.err.println("处理文本消息失败：" + e.getMessage());
                sendTextQuietly(fromUserId, errorReply, contextToken);
            }
        });
    }

    /**
     * 小红书实时采集：识别"南京美食 / 演唱会攻略 / 夏天穿搭"等意图。
     * <p>重要：iLink 协议一条消息只能回一次——所以<b>不发占位</b>，
     * 静默等待采集完成后只发<b>一条</b>最终结果。用户等待 1~2 分钟无回应属正常。
     */
    private boolean handleXhsGuide(String fromUserId, String text, String contextToken) throws IOException {
        String keyword = buildXhsKeyword(fromUserId, text);
        if (keyword == null) return false;

        System.out.println("[小红书] 采集指令: " + truncate(text, 40) + " → 关键词「" + keyword + "」");
        // 异步采集（不阻塞消息循环），完成后只发一条结果
        // 一次外部请求触发一次采集；脚本内部可能瞬时超时/退出码非 0 → 由 XhsCollector 自带 1 次重试
        replyExecutor.submit(() -> {
            try {
                String result = xhsCollector.search(keyword);
                System.out.println("[小红书] 采集完成 " + result.length() + " 字符");
                sendTextReply(fromUserId, result, contextToken);
            } catch (Exception e) {
                // 屏蔽所有底层异常细节（IOException / RuntimeException / Python traceback），
                // 只给用户友好提示。stderr 仅记录类型，绝不打 detail / 堆栈 / 解码后的 raw JSON。
                System.err.println("[小红书] 采集失败（已屏蔽细节）: " + e.getClass().getSimpleName());
                sendTextQuietly(fromUserId,
                        "😅 暂时没有获取到相关攻略内容，请稍后再试～\n（若多次失败，可能是小红书登录状态过期，更新 cookie 即可）",
                        contextToken);
            }
        });
        return true;
    }

    /**
     * 从消息里构建小红书搜索关键词。
     * 城市优先级：文本里的城市 → 该用户之前说过"XX天气"记住的城市 → 全局默认城市。
     * 保证返回的关键词一定带城市，绝不裸搜"美食/攻略"这种全国泛内容（否则返回一堆无关链接）。
     */
    private String buildXhsKeyword(String userId, String text) {
        if (text == null) return null;
        String city = WeatherBriefingSkill.findKnownCity(text);
        if (city == null) city = userCities.getOrDefault(userId, configDefaultCity);
        java.util.regex.Matcher tm = XHS_TOPIC_PATTERN.matcher(text);
        String topic = tm.find() ? tm.group(1) : null;
        // 没匹配到标准主题词时做语义映射："苏州有什么好吃的"→美食，"苏州好玩"→旅游
        if (topic == null) {
            if (text.contains("好吃") || text.contains("吃的") || text.contains("吃")) topic = "美食";
            else if (text.contains("穿") || text.contains("穿搭")) topic = "穿搭";
            else if (text.contains("玩") || text.contains("景点") || text.contains("旅游")) topic = "旅游";
        }
        if (city != null && topic != null) return city + topic;
        if (city != null) return city + "旅游攻略";
        return null; // 无城市无主题 → 不触发
    }

    /**
     * 处理"用X音色说YY"（单音色）和"用三个音色分别说YY"（循环 3 音色）指令。
     * 命中返回 true（已处理），未命中返回 false（继续走正常路由）。
     */
    private boolean tryVoiceCommand(String fromUserId, String text, String contextToken) {
        if (!voiceReplyEnabled || text == null) return false;

        // ===== 三个音色分别说一遍 =====
        Matcher mv = MULTI_VOICE_PATTERN.matcher(text);
        if (mv.find()) {
            // 去掉"一遍/一下/一次"等量词残留："说一遍你好" → "你好"
            String content = mv.group(1).trim().replaceFirst("^(一遍|一下|一次|两遍)", "").trim();
            if (!content.isEmpty()) {
                System.out.println("多音色朗读指令：" + truncate(content, 40));
                String[] voices = {"longanyang", "longxiaochun", "longchen"};
                String[] names = {"龙安洋", "龙小淳", "龙成"};
                for (int i = 0; i < voices.length; i++) {
                    try {
                        byte[] audio = dashScopeClient.textToSpeech(content, voices[i]);
                        client.sendFileWithContext(fromUserId, audio, "语音-" + names[i] + ".mp3", null, contextToken);
                        System.out.println("✅ 已用音色「" + names[i] + "」发送语音 → " + fromUserId);
                    } catch (Exception ex) {
                        System.err.println("音色「" + names[i] + "」合成失败：" + ex.getMessage());
                    }
                }
                return true;
            }
        }

        // ===== 指定单个音色 =====
        Matcher sv = USE_VOICE_PATTERN.matcher(text);
        if (sv.find()) {
            String voiceId = VoiceProfileSkill.resolveVoiceId(sv.group(1));
            String content = sv.group(2).trim();
            if (voiceId != null && !content.isEmpty()) {
                System.out.println("指定音色指令：" + voiceId + " → " + truncate(content, 40));
                try {
                    byte[] audio = dashScopeClient.textToSpeech(content, voiceId);
                    client.sendFileWithContext(fromUserId, audio, "语音回复.mp3", null, contextToken);
                    System.out.println("✅ 已用音色「" + voiceId + "」发送语音 → " + fromUserId);
                } catch (Exception ex) {
                    System.err.println("指定音色 TTS 失败：" + ex.getMessage());
                    sendTextQuietly(fromUserId, "语音合成失败：" + ex.getMessage(), contextToken);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 完整消息路由逻辑，区分三种链路：
     * <ol>
     *   <li><b>Skill 执行链路</b>：命中 Skill 工具（天气/翻译/计算/单位换算）→ 直接返回工具执行结果；</li>
     *   <li><b>RAG 增强回答链路</b>：未命中 Skill 且 enableRag=true → 关键词检索本地文档，
     *       命中片段拼入 Prompt 后交给大模型生成回答；</li>
     *   <li><b>LLM 闲聊兜底链路</b>：Skill、RAG 都未命中 → 直接调用大模型闲聊回复。</li>
     * </ol>
     *
     * @param userId 用户 ID（用于对话历史隔离）
     * @param text   用户消息
     * @return 最终回复文本
     */
    private String routeMessage(String userId, String text) throws IOException {
        // ===== 城市记忆：文本里提到已知城市 → 记住该用户所在城市；
        //       之后"要不要带伞/穿什么"没带城市时，用它而不是全局默认城市 =====
        String cityHint = WeatherBriefingSkill.findKnownCity(text);
        if (cityHint != null) {
            userCities.put(userId, cityHint);
        }
        String userCity = userCities.getOrDefault(userId, configDefaultCity);
        if (!configDefaultCity.equals(userCity)) {
            // 该用户记住了别的城市 → 临时切换天气默认城市（内存级，只影响后续天气查询）
            weather.setDefaultCity(userCity);
            weatherBriefingSkill.setDefaultCity(userCity);
        }

        // ===== 链路 0：专注计时（带 userId 状态，优先级最高）=====
        String focusResult = focusTimerSkill.tryHandle(userId, text);
        if (focusResult != null) {
            System.out.println("[路由] 链路0-FocusTimer：" + truncate(focusResult, 50));
            return focusResult;
        }

        // ===== 链路 0.5：多工具协同规划（Agent 自主拆解 → 顺序调用多个 Skill → LLM 整合）=====
        String planResult = agentPlanner.tryPlan(text);
        if (planResult != null) {
            System.out.println("[路由] 链路0.5-多工具协同：" + truncate(planResult, 60));
            return planResult;
        }

        // ===== 链路 1：Skill 工具执行 =====
        String skillResult = skillRegistry.tryAll(text);
        if (skillResult != null) {
            System.out.println("[路由] 链路1-Skill 执行：" + truncate(skillResult, 50));
            return skillResult;
        }

        // ===== 链路 2：RAG 关键词检索增强 =====
        if (enableRag && localRag != null) {
            String context = localRag.search(text);
            if (context != null && !context.isEmpty()) {
                System.out.println("[路由] 链路2-RAG 增强：命中 " + context.length() + " 字参考资料");
                return doAiChatWithContext(userId, text, context);
            }
        }

        // ===== 链路 3：LLM 闲聊兜底 =====
        System.out.println("[路由] 链路3-LLM 闲聊兜底");
        return doAiChat(userId, text);
    }

    /** RAG 增强版对话：把检索到的文档片段作为"参考资料"拼入 Prompt 再交给大模型 */
    private String doAiChatWithContext(String userId, String userText, String context) throws IOException {
        List<Map<String, String>> history = chatHistories.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(chatMessage("system", systemPrompt));
            }
            messages.add(chatMessage("system",
                    "【参考资料】\n" + context
                            + "\n\n请优先依据参考资料回答用户问题；参考资料中没有的信息，请如实说明你不知道，不要编造。"));
            messages.addAll(history);
            messages.add(chatMessage("user", userText));

            String reply = aiClient.chat(messages);

            history.add(chatMessage("user", userText));
            history.add(chatMessage("assistant", reply));

            int maxItems = maxHistoryTurns * 2;
            while (history.size() > maxItems) {
                history.remove(0);
            }
            return reply;
        }
    }

    /** ✅ 修复：图片消息增加 contextToken */
    private void handleImage(String fromUserId, MessageItem imageItem, String attachedText, String contextToken) {
        replyExecutor.submit(() -> {
            try {
                byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
                if (imageBytes == null || imageBytes.length == 0) {
                    sendTextQuietly(fromUserId, "图片下载失败，请重试", contextToken);
                    return;
                }
                String mime = detectImageMime(imageBytes);
                System.out.println("图片已下载 " + imageBytes.length + " 字节，格式 " + mime);

                List<Map<String, String>> messages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    messages.add(chatMessage("system", systemPrompt));
                }
                String ask = attachedText.isEmpty()
                        ? "（用户发送了一张图片）请查看这张图片，用简短的中文描述图片内容，并友好地回复用户。"
                        : "（用户发送了一张图片）用户的问题/留言是：" + attachedText + "。请查看图片并回答。";
                messages.add(chatMessage("user", ask));

                String reply = visionClient.chatWithImage(messages, imageBytes, mime);
                System.out.println("看图完成，回复 → " + fromUserId + "：" + truncate(reply, 80));
                // ✅ 把触发消息的 contextToken 传给回复
                sendTextReply(fromUserId, reply, contextToken);
            } catch (Exception e) {
                System.err.println("图片处理失败：" + e.getMessage());
                sendTextQuietly(fromUserId, "抱歉，我看图片出了点问题：" + e.getMessage(), contextToken);
            }
        });
    }

    /** ✅ 修复：语音消息增加详细日志和降级方案 */
    private void handleVoice(String fromUserId, MessageItem voiceItem, String contextToken) {
        replyExecutor.submit(() -> {
            try {
                String voiceText = voiceItem.getVoice_item() != null
                        ? voiceItem.getVoice_item().getText() : null;
                if (voiceText == null || voiceText.trim().isEmpty()) {
                    sendTextQuietly(fromUserId, "抱歉，我没能识别出这段语音的内容，请发文字给我吧～", contextToken);
                    return;
                }
                System.out.println("语音已转文字：" + truncate(voiceText, 80));

                // ===== 语音指令同样支持：用龙小淳说你好 / 用三个音色分别说你好（语音路径之前漏了）=====
                if (tryVoiceCommand(fromUserId, voiceText, contextToken)) return;

                // 统一路由：Skill → RAG → LLM 兜底（语音问天气/翻译/计算/单位换算同样生效）
                String reply = routeMessage(fromUserId, voiceText);

                // ✅ 如果开启了语音回复
                if (!tryVoiceReply(fromUserId, reply, contextToken)) {
                    sendTextReply(fromUserId, reply, contextToken);
                }

            } catch (Exception e) {
                System.err.println("语音处理失败：" + e.getMessage());
                sendTextQuietly(fromUserId, errorReply, contextToken);
            }
        });
    }

    /**
     * 尝试用 TTS 合成语音并以文件消息回复；成功返回 true，失败返回 false（调用方回退文字）。
     * 注：iLink 协议当前不支持 bot 发语音条，TTS 音频以"语音回复.mp3"文件发送，点开可播放。
     */
    private boolean tryVoiceReply(String fromUserId, String text, String contextToken) {
        if (!voiceReplyEnabled) return false;
        try {
            byte[] audio = dashScopeClient.textToSpeech(text);
            System.out.println("TTS 合成完成 " + audio.length + " 字节");
            client.sendFileWithContext(fromUserId, audio, "语音回复.mp3", null, contextToken);
            System.out.println("✅ 已发送 TTS 音频文件 → " + fromUserId);
            return true;
        } catch (Exception ttsEx) {
            System.err.println("⚠️ TTS 失败，回退为文字回复：" + ttsEx.getMessage());
            ttsEx.printStackTrace();
            return false;
        }
    }

    private String tryDrawImage(String fromUserId, String text) {
        try {
            byte[] imageBytes = dashScopeClient.generateImage(text);
            System.out.println("画图完成 " + imageBytes.length + " 字节");
            client.sendImage(fromUserId, imageBytes, "image.png", null);
            return null;
        } catch (Exception e) {
            System.err.println("画图失败，回退普通对话：" + e.getMessage());
            return "画图失败";
        }
    }

    private String doAiChat(String userId, String userText) throws IOException {
        List<Map<String, String>> history = chatHistories.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(chatMessage("system", systemPrompt));
            }
            messages.addAll(history);
            messages.add(chatMessage("user", userText));

            String reply = aiClient.chat(messages);

            history.add(chatMessage("user", userText));
            history.add(chatMessage("assistant", reply));

            int maxItems = maxHistoryTurns * 2;
            while (history.size() > maxItems) {
                history.remove(0);
            }
            return reply;
        }
    }

    private static Map<String, String> chatMessage(String role, String content) {
        Map<String, String> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** ✅ 修复：发送文字回复，优先使用触发消息的 context_token */
    private void sendTextReply(String toUserId, String reply, String contextToken) throws IOException {
        String full = (replyPrefix == null ? "" : replyPrefix) + reply;
        client.sendTextWithContext(toUserId, full, contextToken);
        System.out.println("已回复 → " + toUserId + "：" + truncate(reply, 80));
    }

    /** ✅ 修复：静默发送文字（失败不抛异常），优先使用触发消息的 context_token */
    private void sendTextQuietly(String toUserId, String text, String contextToken) {
        try {
            client.sendTextWithContext(toUserId, (replyPrefix == null ? "" : replyPrefix) + text, contextToken);
        } catch (Exception ignore) {}
    }

    /** ✅ 新增：检查 FFmpeg 是否可用 */
    private void checkFFmpeg() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            int code = process.waitFor();
            if (code == 0) {
                System.out.println("✅ FFmpeg 检查通过，语音功能可用");
            } else {
                System.err.println("⚠️ FFmpeg 未正确安装，语音功能可能不可用");
                System.err.println("   请安装 FFmpeg 并确保在 PATH 中");
            }
        } catch (Exception e) {
            System.err.println("⚠️ FFmpeg 检查失败: " + e.getMessage());
            System.err.println("   语音回复将降级为文字回复");
        }
    }

    private static String detectImageMime(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 6 && (b[0] & 0xFF) == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x52 && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private void printQrCode(String content) {
        if (content == null || content.isEmpty()) {
            System.err.println("二维码内容为空");
            return;
        }
        if (content.startsWith("data:image") || isLikelyBase64Image(content)) {
            saveQrImage(content);
            return;
        }
        System.out.println("================ 扫码登录 ================");
        System.out.println("请将以下内容生成二维码后，用微信扫码登录：");
        System.out.println(content);
        System.out.println("==========================================");
    }

    private boolean isLikelyBase64Image(String s) {
        return s.length() > 200 && s.matches("^[A-Za-z0-9+/=\\s]+$");
    }

    private void saveQrImage(String content) {
        try {
            String base64 = content;
            if (base64.contains(",")) {
                base64 = base64.substring(base64.indexOf(',') + 1);
            }
            byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
            java.nio.file.Path file = Paths.get("qrcode.png");
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("================ 扫码登录 ================");
            System.out.println("二维码已保存到: " + file.toAbsolutePath());
            System.out.println("==========================================");
        } catch (Exception e) {
            System.err.println("保存二维码图片失败: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (client != null) {
            try { client.close(); } catch (Exception ignore) {}
        }
        if (aiClient != null) {
            try { aiClient.close(); } catch (Exception ignore) {}
        }
        if (visionClient != null) {
            try { visionClient.close(); } catch (Exception ignore) {}
        }
        if (dashScopeClient != null) {
            try { dashScopeClient.close(); } catch (Exception ignore) {}
        }
        if (weather != null) {
            try { weather.close(); } catch (Exception ignore) {}
        }
        if (translator != null) {
            try { translator.close(); } catch (Exception ignore) {}
        }
        if (rssSkill != null) {
            try { rssSkill.close(); } catch (Exception ignore) {}
        }
        if (xhsCollector != null) {
            try { xhsCollector.close(); } catch (Exception ignore) {}
        }
        if (calculator != null) {
            try { calculator.close(); } catch (Exception ignore) {}
        }
        replyExecutor.shutdownNow();
    }

    private static String resolve(Properties p, String key, String def) {
        String v = resolve(p, key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String resolve(Properties p, String key) {
        String env = System.getenv(key.replace('.', '_').toUpperCase());
        if (env != null && !env.trim().isEmpty()) return env.trim();
        String sys = System.getProperty(key);
        if (sys != null && !sys.trim().isEmpty()) return sys.trim();
        String v = p.getProperty(key);
        return (v == null) ? null : v.trim();
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("ai-bot.properties")) {
            if (in != null) p.load(in);
        } catch (Exception e) {
            System.err.println("加载 ai-bot.properties 失败: " + e.getMessage());
        }
        return p;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static void main(String[] args) throws Exception {
        Properties props = loadConfig();
        String apiKey = resolve(props, "ai.api-key");
        if (apiKey == null || apiKey.isEmpty() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            System.err.println("========================================");
            System.err.println(" [错误] 请先配置 AI API Key！");
            System.err.println(" 在 src/main/resources/ai-bot.properties 中填写 ai.api-key");
            System.err.println("========================================");
            System.exit(1);
        }

        final BotMain bot = new BotMain(props);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("正在关闭 Bot...");
            bot.stop();
        }));

        bot.start();
    }
}