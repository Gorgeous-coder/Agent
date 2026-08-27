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
     * 实时搜索小红书，只返回第一条（高热度）笔记的可打开链接。
     * 用于在 Agent 综合回答末尾追加"参考链接"，一次只给 1 条。
     *
     * @param keyword 搜索关键词（如 "南京 旅游攻略"、"南京 穿搭攻略"）
     * @return 第一条笔记的链接（优先真实 xhslink 短链，其次规范长链）；失败抛 IOException
     */
    public String searchTopNote(String keyword) throws IOException {
        String kw = keyword == null ? "" : keyword.trim();
        List<Note> notes = searchNotes(kw);
        // 优先返回带真实 xhslink 短链的笔记（微信内可点、App 可打开）；
        // 短链生成失败时退回到第一条的规范长链（App/浏览器可打开）。
        for (Note n : notes) {
            if (n.url != null && n.url.contains("xhslink.com")) {
                return n.url;
            }
        }
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
                // 链接优先级：
                // ① 脚本实时生成的官方 xhslink 短链（微信内可点、App 可打开）；
                // ② 无短链时把 search_result/explore 长链改写为 discovery/item 规范长链
                //    （保留 xsec_token，App/浏览器可直接打开）；
                // ③ 都失败则原样保留。
                // ❗ 旧逻辑曾在 Java 端把长链"改写成" http://xhslink.com/o/<id>，
                //    该路径在 xhslink 上不存在 → 微信点击跳到首页、App 提示链接失效。
                String shortlink = n.path("shortlink").asText("").trim();
                note.url = !shortlink.isEmpty() ? shortlink : toCanonicalUrl(url);
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
                // url 已在 parseNotes 里换成：官方短链（优先）或规范长链（兜底）
                sb.append("   🔗 ").append(n.url).append('\n');
            }
            sb.append('\n');
        }
        // 文案重点：让用户知道"上面的 📝 正文摘要 + 👤 作者"已经够用，
        // 链接是 bonus。xhslink 短链在微信/App 都能打开；长链（xiaohongshu.com）
        // 在微信里会被小红书拦截（页面不见），需复制到 App 或手机浏览器打开。
        sb.append("💡 链接说明：开头 xhslink.com 的是官方短链，微信里可直接点、App 能打开；\n");
        sb.append("   其余 xiaohongshu.com 长链请复制到小红书 App 或手机浏览器打开（微信内会提示「页面不见」）。\n");
        sb.append("   📝 上面每篇都给了 500 字正文摘要 + 店铺/地址/玩法等关键信息，其实不点链接也能读全。");
        return sb.toString().trim();
    }

    /**
     * 把脚本返回的长链改写为规范可打开链接（不是伪造短链！）。
     * <p>search_result/explore 长链 → discovery/item/&lt;note_id&gt;（保留全部 query 参数，
     * 含 xsec_token；App/浏览器可直接打开该笔记）。已是 xhslink 短链或改写不动则原样返回。
     */
    private static String toCanonicalUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        // 已是真实短链（脚本生成的 xhslink.com/a/xxx）→ 直接用
        if (url.contains("xhslink.com")) return url;
        try {
            // search_result/<id>?... 或 explore/<id>?... → discovery/item/<id>?...
            // 只改路径段，query（xsec_token / xsec_source 等）原样保留
            String canonical = url.replaceFirst(
                    "/(search_result|explore)/", "/discovery/item/");
            return canonical;
        } catch (Exception ignore) { }
        return url; // 兜底：原样返回
    }
}
