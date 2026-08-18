import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.bot.AiClient;
import com.github.wechat.ilink.sdk.bot.AudioConverter;
import com.github.wechat.ilink.sdk.bot.DashScopeClient;
import com.github.wechat.ilink.sdk.bot.WeatherClient;
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
import java.util.regex.Pattern;

/**
 * 微信 iLink AI Bot 主入口（多模态版）- 全面修复版
 */
public class BotMain {

    private final AiClient aiClient;
    private final AiClient visionClient;
    private final DashScopeClient dashScopeClient;
    private final AudioConverter audioConverter;
    private final WeatherClient weatherClient;
    private final String systemPrompt;
    private final int maxHistoryTurns;
    private final String replyPrefix;
    private final String errorReply;
    private final String unsupportedReply;
    private final boolean imageReplyEnabled;
    private final boolean voiceReplyEnabled;
    private final String defaultCity;

    private final Map<String, List<Map<String, String>>> chatHistories = new ConcurrentHashMap<>();
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-reply");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern DRAW_PATTERN = Pattern.compile(
            "(画|绘制|生成|做|创作|设计).{0,20}(图|图片|照片|壁纸|海报|插画|头像)"
                    + "|(图|图片|照片|壁纸|海报|插画|头像).{0,20}(画|绘制|生成|做|创作|设计)");

    /** 天气意图：消息里包含"天气/气温/冷不冷/热不热/下雨"等关键词 */
    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "天气|气温|温度|冷不冷|热不热|下雨|会不会下雪|台风|空气");
    /** 提取城市：匹配"北京天气" / "天气北京" / "北京的天气" 等 */
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,6}?)(?:市)?(?:今天|明天|后天|现在|的)?天气"
                    + "|(?:今天|明天|后天|现在|的)?天气(.{1,6}?)[，。！？!?\\s]?$");

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
        this.dashScopeClient = new DashScopeClient(
                apiKey,
                resolve(props, "ai.image-model", "wanx2.1-t2i-turbo"),
                resolve(props, "ai.tts-model", "cosyvoice-v2"),
                resolve(props, "ai.tts-voice", "longxiaochun"));

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
        this.defaultCity = resolve(props, "bot.default-city", "深圳");
        this.weatherClient = new WeatherClient();

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

    /** ✅ 修复：文本消息增加 contextToken */
    private void handleText(String fromUserId, String text, String contextToken) {
        replyExecutor.submit(() -> {
            try {
                if (imageReplyEnabled && DRAW_PATTERN.matcher(text).matches()) {
                    System.out.println("检测到画图指令，调用通义万相...");
                    String reply = tryDrawImage(fromUserId, text);
                    if (reply != null) return;
                }

                // ===== 天气意图：查 wttr.in =====
                if (WEATHER_PATTERN.matcher(text).find()) {
                    System.out.println("检测到天气查询指令：" + truncate(text, 40));
                    String city = extractCity(text);
                    try {
                        String weatherText = weatherClient.queryWeather(city);
                        System.out.println("天气查询成功：" + city + " → " + truncate(weatherText, 60));
                        sendTextReply(fromUserId, weatherText, contextToken);
                    } catch (Exception wex) {
                        System.err.println("天气查询失败：" + wex.getMessage());
                        sendTextReply(fromUserId, "抱歉，天气查询失败：" + wex.getMessage(), contextToken);
                    }
                    return;
                }

                String reply = doAiChat(fromUserId, text);
                // ✅ 把触发消息的 contextToken 传给回复，避免用缓存 token
                sendTextReply(fromUserId, reply, contextToken);
            } catch (Exception e) {
                System.err.println("处理文本消息失败：" + e.getMessage());
                sendTextQuietly(fromUserId, errorReply, contextToken);
            }
        });
    }

    /** 从消息文本中提取城市名；提取不到时返回默认城市 */
    private String extractCity(String text) {
        java.util.regex.Matcher m = CITY_PATTERN.matcher(text);
        if (m.find()) {
            String c = m.group(1);
            if (c == null || c.isEmpty()) c = m.group(2);
            if (c != null && !c.trim().isEmpty()) {
                String city = c.trim();
                // 去掉常见干扰词
                city = city.replaceAll("[的了吗呢请问帮我看一下查查]", "").trim();
                if (!city.isEmpty() && city.length() <= 6) {
                    return city;
                }
            }
        }
        return defaultCity;
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

                // ===== 天气意图：语音问天气也要走真实数据（Open-Meteo），
                //      不能交给大模型编（大模型会幻觉出错误温度）=====
                if (WEATHER_PATTERN.matcher(voiceText).find()) {
                    System.out.println("语音检测到天气查询指令：" + truncate(voiceText, 40));
                    String city = extractCity(voiceText);
                    try {
                        String weatherText = weatherClient.queryWeather(city);
                        System.out.println("语音天气查询成功：" + city + " → " + truncate(weatherText, 60));
                        // 语音回复：TTS 朗读真实天气；TTS 失败则回退文字
                        if (!tryVoiceReply(fromUserId, weatherText, contextToken)) {
                            sendTextReply(fromUserId, weatherText, contextToken);
                        }
                    } catch (Exception wex) {
                        System.err.println("语音天气查询失败：" + wex.getMessage());
                        sendTextReply(fromUserId, "抱歉，天气查询失败：" + wex.getMessage(), contextToken);
                    }
                    return;
                }

                String reply = doAiChat(fromUserId, voiceText);

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
        if (weatherClient != null) {
            try { weatherClient.close(); } catch (Exception ignore) {}
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