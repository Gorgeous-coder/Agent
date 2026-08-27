package com.github.wechat.ilink.sdk.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专注力计时 / 番茄钟 Skill（业务层，非 SDK 源码）。
 *
 * <p>每个用户独立一个计时器（按 userId 隔离）。
 * 触发词示例：
 * <ul>
 *   <li>"开始专注 25 分钟" / "番茄钟开始" / "专注 45 分钟" → 开始计时（默认 25 分钟）</li>
 *   <li>"暂停专注" / "暂停" → 暂停</li>
 *   <li>"继续专注" / "继续" → 继续</li>
 *   <li>"结束专注" / "取消" / "结束" → 取消</li>
 *   <li>"还剩多久" / "查询专注" / "状态" → 查询</li>
 * </ul>
 *
 * <p>状态机：IDLE → RUNNING → (PAUSED ↔ RUNNING) → FINISHED / CANCELLED。
 * 内存管理（重启 bot 会清空）。到点不会主动推送，下次用户查询时提示已结束。
 */
public class FocusTimerSkill implements Skill {

    /** "开始专注" / "番茄钟开始" / "专注" / "倒计时"（数字和单位由 extractSeconds 单独处理） */
    private static final Pattern START_PATTERN = Pattern.compile(
            "(?:开始)?(?:专注|番茄钟|倒计时|计时)");
    /** "暂停专注" / "暂停一下" / "暂停" */
    private static final Pattern PAUSE_PATTERN = Pattern.compile("(?:暂停|停一下)");
    /** "继续专注" / "继续" / "恢复" */
    private static final Pattern RESUME_PATTERN = Pattern.compile("(?:继续专注|继续|恢复)");
    /** "结束专注" / "结束" / "取消专注" / "取消" / "停掉" */
    private static final Pattern CANCEL_PATTERN = Pattern.compile("(?:结束专注|取消专注|停掉专注|结束|取消|停掉)");
    /** "还剩多久" / "查询专注" / "专注状态" / "查一下" */
    private static final Pattern STATUS_PATTERN = Pattern.compile("(?:还剩多久|查询专注|专注状态|当前状态|查一下|看看)");

    /** 最少 30 秒（更短的 timer 没意义） */
    private static final int MIN_SECONDS = 30;
    /** 最多 4 小时 */
    private static final int MAX_SECONDS = 4 * 3600;

    private final Map<String, TimerState> states = new HashMap<>();

    @Override
    public String name() {
        return "专注计时";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        // Skill 的 fromUserId 由 BotMain 通过 call(userId, text) 传入更准确，
        // 但 tryHandle 只有 text。这里采用固定一个 userId "default"。
        // 注意：实际部署时 BotMain 应包一层传 userId；见 BotMain 注册时的适配器。
        return handle(text, "default");
    }

    /** 暴露给 BotMain 调用的版本（带 userId） */
    public String tryHandle(String userId, String text) {
        if (text == null || text.isEmpty()) return null;
        return handle(text, userId);
    }

    private String handle(String text, String userId) {
        // 优先级：状态查询 > 取消 > 暂停 > 继续 > 开始
        // 原因：用户说"还剩多久 倒计时结束"同时含查询词和"倒计时"（START_PATTERN 也会匹配），
        // 必须先命中查询，否则会被误当成"开始新计时"并重置 timer（旧 bug）
        if (STATUS_PATTERN.matcher(text).find()) return status(userId);
        if (CANCEL_PATTERN.matcher(text).find()) return cancel(userId);
        if (PAUSE_PATTERN.matcher(text).find()) return pause(userId);
        if (RESUME_PATTERN.matcher(text).find()) return resume(userId);
        if (START_PATTERN.matcher(text).find()) return start(userId, extractSeconds(text));
        return null;
    }

    /**
     * 从文本里提取时长（秒），支持：
     * <ul>
     *   <li>"25 分钟" / "25 分" / "25 min" → 1500 秒</li>
     *   <li>"50 秒" / "50s" → 50 秒</li>
     *   <li>"1 小时" / "1h" / "1 时" → 3600 秒</li>
     *   <li>"1 小时 30 分" / "1 小时 30 分钟" → 5400 秒</li>
     *   <li>"90"（纯数字无单位）→ 90 分钟（兜底，向后兼容）</li>
     * </ul>
     * 没找到数字返回 0（由 start() 兜底为 25 分钟）。
     */
    private static int extractSeconds(String text) {
        if (text == null || text.isEmpty()) return 0;
        int hours = 0, minutes = 0, seconds = 0;

        // 1) 小时：必须含"小时"或"hour"
        Matcher m = Pattern.compile("(\\d+)\\s*(?:小时|hour|h)(?![a-zA-Z])").matcher(text);
        if (m.find()) {
            try { hours = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
        }
        // 2) 分钟：先匹配"分钟"（优先覆盖"分钟"整词），再匹配单独的"分" / min / m
        m = Pattern.compile("(\\d+)\\s*分钟").matcher(text);
        if (m.find()) {
            try { minutes = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
        } else {
            m = Pattern.compile("(\\d+)\\s*(?:min|分)(?!钟|钟)").matcher(text);
            if (m.find()) {
                try { minutes = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
            }
        }
        // 3) 秒：匹配"秒钟"或"秒"
        m = Pattern.compile("(\\d+)\\s*(?:秒钟|秒|s)(?!分|时)").matcher(text);
        if (m.find()) {
            try { seconds = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
        }

        int total = hours * 3600 + minutes * 60 + seconds;
        if (total > 0) return total;

        // 兜底：纯数字无单位，按分钟处理（向后兼容老用法）
        m = Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) * 60; } catch (NumberFormatException ignore) { }
        }
        return 0;
    }

    private String start(String userId, int minutes) {
        // 入参是秒数（重命名变量以兼容历史，但单位是秒）
        int totalSec = minutes;
        if (totalSec <= 0) totalSec = 25 * 60;  // 默认 25 分钟
        if (totalSec < MIN_SECONDS) totalSec = MIN_SECONDS;  // 最少 30 秒
        if (totalSec > MAX_SECONDS) totalSec = MAX_SECONDS;  // 最多 4 小时
        // 守护：已有 RUNNING/PAUSED 的计时不允许直接覆盖（防止"还剩多久 倒计时结束"误触发 start）
        TimerState existing = states.get(userId);
        if (existing != null && (existing.status == Status.RUNNING || existing.status == Status.PAUSED)) {
            long elapsedMs = System.currentTimeMillis() - existing.startedAt - existing.pausedTotalMs
                    - (existing.status == Status.PAUSED ? System.currentTimeMillis() - existing.pauseAt : 0);
            long remainingMs = existing.durationMs - elapsedMs;
            if (remainingMs > 0) {
                return "⚠️ 已有专注计时在进行中（还剩 " + formatMs(remainingMs) + "）。\n"
                        + "说'结束'取消当前计时后，再说'开始专注 " + formatDuration(totalSec) + "'开启新一轮。";
            }
        }
        TimerState s = new TimerState();
        s.startedAt = System.currentTimeMillis();
        s.durationMs = totalSec * 1000L;
        s.status = Status.RUNNING;
        s.pausedTotalMs = 0;
        states.put(userId, s);
        long endTime = s.startedAt + s.durationMs;
        String endTimeStr = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(endTime));
        return "🍅 专注计时开始！\n"
                + "⏱ 时长 " + formatDuration(totalSec) + "\n"
                + "🏁 预计结束 " + endTimeStr + "\n"
                + "💡 说'暂停'/'继续'/'结束'/'还剩多久'可管理计时";
    }

    private String pause(String userId) {
        TimerState s = states.get(userId);
        if (s == null || s.status == Status.IDLE) return "⚠️ 当前没有正在运行的专注计时";
        if (s.status == Status.PAUSED) return "⏸️ 已经在暂停状态了，无需再次暂停";
        if (s.status == Status.FINISHED) return "✅ 上轮专注已结束（" + s.getDurationLabel() + "）。说'开始专注 25 分钟'再来一轮吧～";
        s.pauseAt = System.currentTimeMillis();
        s.status = Status.PAUSED;
        return "⏸️ 专注已暂停。说'继续'恢复计时。";
    }

    private String resume(String userId) {
        TimerState s = states.get(userId);
        if (s == null || s.status == Status.IDLE) return "⚠️ 当前没有暂停中的专注计时";
        if (s.status == Status.RUNNING) return "▶️ 专注正在运行中，无需继续";
        if (s.status == Status.FINISHED) return "✅ 上轮专注已结束。说'开始专注 25 分钟'再来一轮吧～";
        s.pausedTotalMs += (System.currentTimeMillis() - s.pauseAt);
        s.status = Status.RUNNING;
        return "▶️ 专注已恢复。";
    }

    private String cancel(String userId) {
        TimerState s = states.get(userId);
        if (s == null || s.status == Status.IDLE) return "⚠️ 当前没有运行中的专注计时";
        states.remove(userId);
        return "🛑 专注计时已取消。";
    }

    private String status(String userId) {
        TimerState s = states.get(userId);
        if (s == null || s.status == Status.IDLE) return "💤 当前没有专注计时。说'开始专注 25 分钟'（也支持秒/小时）开始吧～";
        if (s.status == Status.FINISHED) {
            states.remove(userId);
            return "✅ 上轮专注已完成（" + s.getDurationLabel() + "）。说'开始专注 25 分钟'再来一轮吧～";
        }
        long elapsedMs = System.currentTimeMillis() - s.startedAt - s.pausedTotalMs
                - (s.status == Status.PAUSED ? System.currentTimeMillis() - s.pauseAt : 0);
        long remainingMs = s.durationMs - elapsedMs;
        if (remainingMs <= 0) {
            s.status = Status.FINISHED;
            return "🎉 专注时间到！休息 5 分钟吧～说'开始专注 25 分钟'再来一轮";
        }
        if (s.status == Status.PAUSED) {
            return "⏸️ 专注暂停中。已专注 " + formatMs(elapsedMs) + "，还剩 " + formatMs(remainingMs);
        }
        return "🍅 专注运行中...\n⏱ 已专注 " + formatMs(elapsedMs) + "\n⏳ 还剩 " + formatMs(remainingMs);
    }

    private static String formatMs(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        if (totalSec >= 3600) {
            long h = totalSec / 3600;
            long min = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            if (min > 0) return h + " 小时 " + min + " 分 " + s + " 秒";
            return h + " 小时 " + s + " 秒";
        }
        if (totalSec >= 60) {
            long min = totalSec / 60;
            long s = totalSec % 60;
            if (s > 0) return min + " 分 " + s + " 秒";
            return min + " 分";
        }
        return totalSec + " 秒";
    }

    /** 把秒数格式化成人类可读（"1 小时 30 分" / "50 秒" / "25 分钟"） */
    private static String formatDuration(int sec) {
        if (sec >= 3600) {
            int h = sec / 3600;
            int rem = sec % 3600;
            StringBuilder sb = new StringBuilder().append(h).append(" 小时");
            if (rem > 0) sb.append(" ").append(formatDuration(rem));
            return sb.toString();
        }
        if (sec >= 60) {
            int min = sec / 60;
            int rem = sec % 60;
            StringBuilder sb = new StringBuilder().append(min).append(" 分钟");
            if (rem > 0) sb.append(" ").append(rem).append(" 秒");
            return sb.toString();
        }
        return sec + " 秒";
    }

    private enum Status { IDLE, RUNNING, PAUSED, FINISHED }

    private static class TimerState {
        long startedAt;
        long durationMs;
        long pauseAt;
        long pausedTotalMs;
        Status status = Status.IDLE;

        String getDurationLabel() {
            return formatDuration((int) (durationMs / 1000L));
        }
    }
}