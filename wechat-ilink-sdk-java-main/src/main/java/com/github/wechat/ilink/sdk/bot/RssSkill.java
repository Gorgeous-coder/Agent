package com.github.wechat.ilink.sdk.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * RSS 订阅监控（半自动化新攻略推送）。
 *
 * <p>原理：每 N 分钟拉一次配置的 RSS 源，发现新条目时通过回调推送给活跃用户。
 * 无需 cookie / 无需登录 / 不触发平台风控，适合长期关注少量主题。
 *
 * <p>配置：项目根 rss-feeds.properties（bot 工作目录），每行：名称|URL。
 *
 * <p>推送：构造时传入 onNewItem 回调（BotMain 负责发到微信）。
 */
public class RssSkill implements AutoCloseable {

    private final String configPath;
    private final Consumer<String> onNewItem;
    private final OkHttpClient http;
    private final List<Feed> feeds = new ArrayList<>();
    /** 已读条目（用 guid 做 key，新条目不会重复推送） */
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    /** 活跃用户集合（推送目标） */
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    /** 自上次启动以来的首次拉取（baseline），不推送；之后的新条目才推 */
    private volatile boolean firstRun = true;

    /** 单个 RSS 源 */
    public static final class Feed {
        final String name;
        final String url;
        public Feed(String name, String url) { this.name = name; this.url = url; }
        public String name() { return name; }
        public String url() { return url; }
    }

    public RssSkill(String configPath, Consumer<String> onNewItem) {
        this.configPath = configPath == null || configPath.trim().isEmpty()
                ? "rss-feeds.properties" : configPath.trim();
        this.onNewItem = onNewItem;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rss-scheduler");
            t.setDaemon(true);
            return t;
        });
        loadFeeds();
    }

    public void addActiveUser(String userId) { if (userId != null) activeUsers.add(userId); }
    public void removeActiveUser(String userId) { activeUsers.remove(userId); }
    public int getActiveUserCount() { return activeUsers.size(); }
    public int getFeedCount() { return feeds.size(); }
    public List<Feed> getFeeds() { return java.util.Collections.unmodifiableList(feeds); }

    /** 启动定时拉取（首次 30s 跑一次做 baseline，之后每 5 分钟一次） */
    public void start() {
        if (feeds.isEmpty()) {
            System.out.println("[RssSkill] 未配置任何 RSS 源（" + configPath + " 为空或不存在）");
            return;
        }
        System.out.println("[RssSkill] 已加载 " + feeds.size() + " 个 RSS 源（首次 30s 拉 baseline，之后每 5 分钟）");
        scheduler.scheduleAtFixedRate(this::pollAll, 30, 300, TimeUnit.SECONDS);
    }

    /** 手动立刻拉一次（用于测试） */
    public int pollNow() {
        return pollAll();
    }

    private int pollAll() {
        int newCount = 0;
        for (Feed f : feeds) {
            try {
                List<Item> items = fetchAndParse(f);
                for (Item it : items) {
                    String key = f.name + "::" + it.link;
                    if (seen.putIfAbsent(key, Boolean.TRUE) == null) {
                        if (!firstRun) {
                            String msg = format(f.name, it);
                            notifyUsers(msg);
                            newCount++;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[RssSkill] 拉取失败 " + f.name + ": " + e.getMessage());
            }
        }
        if (firstRun) {
            System.out.println("[RssSkill] baseline 完成：共 " + seen.size() + " 个历史条目已缓存（首次不推送）");
            firstRun = false;
        } else if (newCount > 0) {
            System.out.println("[RssSkill] 本轮新增 " + newCount + " 条");
        }
        return newCount;
    }

    private void notifyUsers(String msg) {
        for (String uid : activeUsers) {
            try {
                onNewItem.accept(buildPushPayload(uid, msg));
            } catch (Exception e) {
                System.err.println("[RssSkill] 推送失败 " + uid + ": " + e.getMessage());
            }
        }
    }

    /** 让 BotMain 把推送拼成自己的格式（用 userId 上下文） */
    private String buildPushPayload(String userId, String msg) {
        return userId + "\n" + msg;
    }

    private String format(String feedName, Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("📡 ").append(feedName).append("\n\n");
        sb.append("📌 ").append(it.title == null ? "(无标题)" : it.title).append("\n");
        if (it.summary != null && !it.summary.isEmpty() && !it.summary.equals(it.title)) {
            String s = it.summary.length() > 200 ? it.summary.substring(0, 200) + "..." : it.summary;
            sb.append(s).append("\n");
        }
        if (it.link != null) sb.append("🔗 ").append(it.link).append("\n");
        return sb.toString().trim();
    }

    // ==================== 加载配置 ====================

    private void loadFeeds() {
        Properties p = new Properties();
        try {
            if (Files.exists(Paths.get(configPath))) {
                p.load(Files.newBufferedReader(Paths.get(configPath), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("[RssSkill] 读取配置失败: " + e.getMessage());
        }
        Set<String> seenNames = new LinkedHashSet<>();
        for (String key : p.stringPropertyNames()) {
            String url = p.getProperty(key).trim();
            if (url.isEmpty() || url.startsWith("#")) continue;
            feeds.add(new Feed(key, url));
            seenNames.add(key + "|" + url);
        }
        System.out.println("[RssSkill] 从 " + configPath + " 加载 " + feeds.size() + " 个 RSS 源");
    }

    // ==================== 拉取 + 解析 ====================

    private List<Item> fetchAndParse(Feed f) throws IOException {
        String xml;
        Request req = new Request.Builder()
                .url(f.url)
                .header("User-Agent", "wechat-ilink-bot/1.0 (rss-monitor)")
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            xml = resp.body().string();
        }
        return parse(xml);
    }

    private static final Pattern ITEM_PATTERN =
            Pattern.compile("<item\\b[^>]*>([\\s\\S]*?)</item>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN(String tag) {
        return Pattern.compile("<" + tag + "\\b[^>]*>([\\s\\S]*?)</" + tag + ">", Pattern.CASE_INSENSITIVE);
    }
    private static final Pattern CDATA_PATTERN = Pattern.compile("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>");

    /** 简化的 RSS 2.0 item 解析（处理 CDATA + 嵌套标签） */
    private List<Item> parse(String xml) {
        List<Item> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        Matcher items = ITEM_PATTERN.matcher(xml);
        while (items.find()) {
            String block = items.group(1);
            Item it = new Item();
            it.title = clean(extract(block, "title"));
            it.link = clean(extract(block, "link"));
            it.summary = clean(extract(block, "description"));
            it.pubDate = clean(extract(block, "pubDate"));
            if (it.link == null || it.link.isEmpty()) {
                it.link = clean(extract(block, "guid"));
            }
            if (it.title == null || it.title.isEmpty()) continue;
            if (it.link == null || it.link.isEmpty()) continue;
            out.add(it);
        }
        return out;
    }

    private String extract(String block, String tag) {
        Matcher m = TAG_PATTERN(tag).matcher(block);
        if (!m.find()) return null;
        String content = m.group(1).trim();
        // 处理 CDATA
        Matcher cdata = CDATA_PATTERN.matcher(content);
        if (cdata.find()) content = cdata.group(1);
        return content;
    }

    /** 去除 HTML 标签和多余空白 */
    private String clean(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static final class Item {
        String title, link, summary, pubDate;
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
