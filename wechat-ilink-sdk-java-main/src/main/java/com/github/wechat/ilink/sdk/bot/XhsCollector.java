package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 小红书实时采集器（Playwright + 系统 Edge 方案）。
 *
 * <p>每次搜索启动一个独立的 Python + Playwright 进程（headless Edge），
 * 打开小红书搜索页提取笔记卡片。进程用完即退，无常驻 daemon，不会卡死 bot。
 *
 * <p>依赖：已安装 Python venv（cookieenv）+ playwright；采集脚本 redbook_crawl.py
 * 位于项目根（BotMain 工作目录）。
 *
 * <p>注意：cookie 会过期（几小时~几天），失效时脚本输出空结果，
 * 需重新从浏览器 DevTools 复制 web_session / a1 / webId 更新配置。
 */
public class XhsCollector implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Python venv 解释器（装有 playwright） */
    private static final String PYTHON =
            "C:\\Users\\DELL\\.workbuddy\\binaries\\python\\envs\\cookieenv\\Scripts\\python.exe";
    /** 采集脚本路径（BotMain 工作目录，即项目根） */
    private static final String SCRIPT =
            System.getProperty("user.dir") + "\\redbook_crawl.py";

    private final String webSession;
    private final String a1;
    private final String webId;
    private final int topN;

    public XhsCollector(String webSession, String a1, String webId) {
        this(webSession, a1, webId, 8);
    }

    public XhsCollector(String webSession, String a1, String webId, int topN) {
        this.webSession = webSession == null ? "" : webSession.trim();
        this.a1 = a1 == null ? "" : a1.trim();
        this.webId = webId == null ? "" : webId.trim();
        this.topN = Math.max(3, Math.min(topN, 15));
    }

    /** cookie 是否已配置（三个值都非空） */
    public boolean isConfigured() {
        return !webSession.isEmpty() && !a1.isEmpty() && !webId.isEmpty();
    }

    /**
     * 实时搜索小红书，返回格式化后的笔记列表文本。
     *
     * @param keyword 搜索关键词（如 "南京美食"、"夏天穿搭"）
     * @return 适合微信发送的文本；失败抛 IOException
     */
    public String search(String keyword) throws IOException {
        String kw = keyword == null ? "" : keyword.trim();
        List<Note> notes = searchNotes(kw);
        return format(notes, kw);
    }

    /**
     * 实时搜索小红书，只返回第一条（高热度）笔记的链接（已转 xhslink 短链）。
     * 用于在 Agent 综合回答末尾追加"参考链接"，一次只给 1 条。
     *
     * @param keyword 搜索关键词（如 "南京 旅游攻略"、"南京 穿搭"）
     * @return 第一条笔记的 xhslink 链接；失败抛 IOException
     */
    public String searchTopNote(String keyword) throws IOException {
        String kw = keyword == null ? "" : keyword.trim();
        List<Note> notes = searchNotes(kw);
        Note first = notes.get(0);
        return (first.url == null || first.url.isEmpty()) ? "" : first.url;
    }

    /** 内部：实时采集笔记列表（带 1 次重试），供 search / searchTopNote 复用 */
    private List<Note> searchNotes(String kw) throws IOException {
        if (!isConfigured()) {
            throw new IOException("小红书 cookie 未配置，请在 ai-bot.properties 填写 xhs.cookie.*");
        }
        if (kw.isEmpty()) {
            throw new IOException("搜索关键词为空");
        }
        System.out.println("[XhsCollector] 实时采集「" + kw + "」...");

        // 一次采集失败（超时 / 退出码非 0 / Python 抛错）属于瞬时问题，
        // 重试一次能把 Playwright 启动开销、白屏、风控等多发一次解决掉。
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return runScriptOnce(kw, attempt);
            } catch (IOException e) {
                last = e;
                System.err.println("[XhsCollector] 第 " + attempt + " 次失败：" + e.getMessage()
                        + (attempt < 2 ? "，重试中..." : ""));
                // 第 2 次还失败 → 抛
            }
        }
        throw last != null ? last : new IOException("采集失败（未知原因）");
    }

    /** 实际跑一次 Python 脚本（被 searchNotes 的重试循环调用），返回笔记列表 */
    private List<Note> runScriptOnce(String kw, int attempt) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON, "-X", "utf8", SCRIPT,
                "--session", webSession,
                "--a1", a1,
                "--webid", webId,
                "--keyword", kw,
                "--top", String.valueOf(topN),
                "--detail", String.valueOf(topN));  // 默认所有笔记都抓正文
        // ✅ Windows console 默认 gbk，python 输出含生僻汉字会爆 UnicodeEncodeError。
        // 双重保险：ProcessBuilder 环境里强制 utf-8（脚本里也 reconfigure 兜底）。
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        // ✅ Python 默认是 block-buffered，连接到 pipe（Java ProcessBuilder）时
        // 不显式 flush 会导致 parent 拿不到末尾输出。强制 unbuffered + utf-8。
        pb.environment().put("PYTHONUNBUFFERED", "1");

        Process p;
        java.io.ByteArrayOutputStream stdoutBuf = new java.io.ByteArrayOutputStream();
        try {
            p = pb.start();
            // 关键：Java ProcessBuilder 默认不会主动把 stdout pipe 给 Python 读完，
            // pipe 满（Linux 64KB / Windows 也有限）后 Python 会卡在 stdout.write。
            // 起两个守护线程读 stdout / stderr：stdout 收集到 stdoutBuf，stderr 转储到自己的 stderr。
            Thread drainOut = new Thread(() -> drainIntoBuffer(p.getInputStream(), stdoutBuf), "xhs-stdout-drain");
            Thread drainErr = new Thread(() -> drainToErr(p.getErrorStream()), "xhs-stderr-drain");
            drainOut.setDaemon(true);
            drainErr.setDaemon(true);
            drainOut.start();
            drainErr.start();
            try { p.getOutputStream().close(); } catch (Exception ignore) { }
        } catch (IOException e) {
            throw new IOException("无法启动采集脚本（检查 Python/Playwright 是否安装）：" + e.getMessage());
        }

        boolean done;
        try {
            // 120s 上限：直接调用 ~31s（8 篇详情 ≈ 60-90s），留足余量；
            // AgentPlanner 管线里另有 110s 兜底，这里 120s 单次 + 上层重试即可。
            done = p.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("小红书攻略获取中断，请稍后重试");
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("小红书攻略获取超时（120s），请稍后重试或换个关键词");
        }
        // 等 drain 线程结束（最多再等 2s，防止它仍在收尾）
        try {
            int waitedMs = 0;
            while (waitedMs < 2000) {
                Thread.sleep(50);
                waitedMs += 50;
                // 简单判断：getInputStream().available()==0 视为已读完
                try {
                    if (p.getInputStream().available() == 0) break;
                } catch (Exception ignore) { break; }
            }
        } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        String out = stdoutBuf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        if (p.exitValue() != 0) {
            System.err.println("[XhsCollector] Python 退出码 " + p.exitValue()
                    + "（stderr 已转储到 [Python stderr] 行）");
            throw new IOException("小红书攻略暂时获取失败，请稍后重试");
        }
        List<Note> notes = parseNotes(out);
        if (notes.isEmpty()) {
            throw new IOException("暂时没有获取到相关攻略内容，请稍后重试");
        }
        System.out.println("[XhsCollector] 采集完成 " + notes.size() + " 条");
        return notes;
    }

    /** 把 pipe 数据读入指定 ByteArrayOutputStream（防止 pipe 阻塞 + 保留脚本输出） */
    private static void drainIntoBuffer(java.io.InputStream in, java.io.ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignore) { }
    }

    /** 把 stderr 数据读完并打到我们自己 stderr，user 看不到 */
    private static void drainToErr(java.io.InputStream in) {
        try {
            byte[] buf = new byte[4096];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
            String msg = sb.toString().trim();
            if (!msg.isEmpty()) System.err.println("[Python stderr] " + msg);
        } catch (Exception ignore) { }
    }

    @Override
    public void close() {
        // 无常驻资源，无需清理
    }

    // ==================== 内部实现 ====================

    private static final class Note {
        String title, author, likes, url, content;
    }

    /** 解析脚本 stdout 的 JSON 数组（可能带外层引号） */
    private List<Note> parseNotes(String raw) {
        List<Note> result = new ArrayList<>();
        try {
            String s = raw == null ? "" : raw.trim();
            if (s.isEmpty()) return result;
            JsonNode arr = MAPPER.readTree(s);
            if (arr != null && arr.isTextual()) {
                arr = MAPPER.readTree(arr.asText());
            }
            if (arr == null || !arr.isArray()) return result;
            for (JsonNode n : arr) {
                String title = n.path("title").asText("").trim();
                String url = n.path("url").asText("").trim();
                if (title.isEmpty() || url.isEmpty()) continue;
                Note note = new Note();
                note.title = title;
                note.author = n.path("author").asText("").trim();
                note.likes = n.path("likes").asText("").trim();
                // 把 search_result/explore 长链转成 xhslink 短链：微信里点长链一律"页面不见"，
                // xhslink 走微信 OAuth（至少能进小红书，不被屏蔽），未授权回首页，已授权 iOS/Android
                // 会弹"在 App 打开"，覆盖绝大多数用户场景。
                note.url = toXhsShortLink(url);
                note.content = n.path("content").asText("").trim();
                result.add(note);
            }
        } catch (Exception e) {
            System.err.println("[XhsCollector] 解析失败（已忽略）: " + e.getMessage());
        }
        return result;
    }

    private String format(List<Note> notes, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("📖 小红书「").append(keyword).append("」热门攻略 Top ").append(Math.min(notes.size(), topN)).append("：\n\n");
        int count = Math.min(notes.size(), topN);
        for (int i = 0; i < count; i++) {
            Note n = notes.get(i);
            sb.append(i + 1).append(". ").append(n.title).append('\n');
            String meta = (n.author == null ? "" : n.author)
                    + (n.likes == null || n.likes.isEmpty() ? "" : "  👍" + n.likes);
            if (!meta.trim().isEmpty()) sb.append("   👤 ").append(meta.trim()).append('\n');
            // 正文优先：500 字摘要（之前 350 字偏短），爬不到也明确告知，不裸甩链接
            if (n.content != null && !n.content.isEmpty()) {
                String body = n.content.replaceAll("\\s+", " ").trim();
                // 去掉开头的纯符号/emoji 残留。Java 正则的 \W 会把中文当"非词"，所以只 strip 标点+符号+空白
                body = body.replaceAll("^[\\p{P}\\p{S}\\s]+", "").trim();
                if (body.length() > 500) body = body.substring(0, 500) + "…";
                sb.append("   📝 ").append(body).append('\n');
            } else {
                sb.append("   📝 （这篇没能抓到正文摘要，只给你标题和链接）\n");
            }
            if (n.url != null && !n.url.isEmpty()) {
                // url 已经在 parseNotes 里转成 xhslink 短链（在微信里能走 OAuth）
                sb.append("   🔗 ").append(n.url).append('\n');
            }
            sb.append('\n');
        }
        // 文案重点：让用户知道"上面的 📝 正文摘要 + 👤 作者"已经够用，
        // 链接是 bonus（小红书 App/已绑定微信账号才能看）；教用户怎么在 App 里搜到原帖。
        sb.append("💡 微信里点不开是正常的（小红书对未登录用户一律「页面不见」）。想看完整原帖有 2 个办法：\n");
        sb.append("   ① 复制上方「标题 + 作者」→ 打开小红书 App → 搜索 → 找到原帖\n");
        sb.append("   ② 复制「🔗 链接」到手机浏览器打开（需已登录小红书）\n");
        sb.append("   📝 上面每篇都给了 500 字正文摘要 + 店铺/地址/玩法等关键信息，其实不点链接也能读全。");
        return sb.toString().trim();
    }

    /**
     * 把小红书长链转成 xhslink 短链（避免微信内置浏览器"页面不见"）。
     * <p>支持 search_result 与 explore 两种 URL。提取 24 位 hex note_id 拼成
     * <code>http://xhslink.com/o/&lt;id&gt;</code>。
     * <p>若 URL 已经是 xhslink 短链或解析不到 note_id，原样返回（让 Java 端兜底显示）。
     */
    private static String toXhsShortLink(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            // search_result/<id>?... 或 explore/<id>?... 都提取中间那段 24 位 hex
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?:search_result|explore)/([0-9a-f]{20,})").matcher(url);
            if (m.find()) {
                return "http://xhslink.com/o/" + m.group(1);
            }
        } catch (Exception ignore) { }
        return url; // 兜底：原样返回，至少在小红书 App/已登录浏览器里能用
    }
}
