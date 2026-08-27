package com.github.wechat.ilink.sdk.bot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间解析工具（业务层，非 SDK 源码）。
 *
 * <p>把中文口语时间解析成可用的数值：
 * <ul>
 *   <li>{@link #parseClockTime(String)}：解析一天内的时刻 → "HH:mm"（用于作息设置）</li>
 *   <li>{@link #parseDueTime(String)}：解析提醒触发时刻 → epoch 毫秒（用于提醒创建）</li>
 * </ul>
 *
 * <p>支持的写法：
 * <ul>
 *   <li>"早上7点" / "7:00" / "七点半" / "下午3点" / "晚上6点30"</li>
 *   <li>"明天9点" / "明天下午3点" / "5分钟后" / "1小时后" / "今天15:30"</li>
 * </ul>
 */
public final class ScheduleUtil {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private ScheduleUtil() {}

    /**
     * 解析一天内的时刻。
     *
     * @return "HH:mm"（24 小时制）；解析失败返回 null
     */
    public static String parseClockTime(String text) {
        if (text == null || text.isEmpty()) return null;
        // 1) "7:30" / "7：30"
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").matcher(text);
        if (m.find()) {
            try {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                return normalizeHour(text, h, min);
            } catch (NumberFormatException ignore) { }
        }
        // 2) "早上7点" / "7点" / "下午3点" / "晚上6点"
        m = Pattern.compile("(\\d{1,2})\\s*点\\s*(半|30|一刻|45|\\d{1,2})?").matcher(text);
        if (m.find()) {
            try {
                int h = Integer.parseInt(m.group(1));
                int min = 0;
                String half = m.group(2);
                if ("半".equals(half)) min = 30;
                else if ("30".equals(half)) min = 30;
                else if ("一刻".equals(half)) min = 15;
                else if ("45".equals(half)) min = 45;
                else if (half != null && !half.isEmpty()) {
                    try { min = Integer.parseInt(half); } catch (NumberFormatException ignore) { }
                }
                return normalizeHour(text, h, min);
            } catch (NumberFormatException ignore) { }
        }
        return null;
    }

    /** 处理 12/24 小时制的上午/下午/晚上/凌晨 */
    private static String normalizeHour(String text, int h, int min) {
        int hour = h;
        if (h <= 12) {
            if (text.contains("下午") || text.contains("晚上") || text.contains("傍晚")) {
                if (h < 12) hour = h + 12;
            } else if (text.contains("凌晨") || text.contains("午夜")) {
                if (h == 12) hour = 0;
            } else if (text.contains("中午") || text.contains("正午")) {
                if (h < 12) hour = 12;
            }
            // "上午/早上/清晨" 或没有前缀：12 点按 12:00，其他原样
        }
        if (hour > 23) hour = 23;
        if (min > 59) min = 59;
        return String.format("%02d:%02d", hour, min);
    }

    /**
     * 解析提醒触发时刻（相对/绝对）。
     *
     * @return epoch 毫秒；解析失败返回 -1
     */
    public static long parseDueTime(String text) {
        if (text == null || text.isEmpty()) return -1L;
        long now = System.currentTimeMillis();

        // 1) 相对时间：X分钟后 / X小时后 / X分钟后
        Matcher m = Pattern.compile("(\\d+)\\s*(?:分钟|分)后").matcher(text);
        if (m.find()) {
            try { return now + Long.parseLong(m.group(1)) * 60_000L; } catch (NumberFormatException ignore) { }
        }
        m = Pattern.compile("(\\d+)\\s*(?:小时|个小时)后").matcher(text);
        if (m.find()) {
            try { return now + Long.parseLong(m.group(1)) * 3600_000L; } catch (NumberFormatException ignore) { }
        }

        // 2) 绝对时间：明天/今天/无前缀 + 时刻
        boolean tomorrow = text.contains("明天") || text.contains("明早") || text.contains("明日");
        boolean dayAfter = text.contains("后天");
        String clock = parseClockTime(text);
        if (clock == null) return -1L;

        LocalDateTime base;
        if (dayAfter) {
            base = LocalDate.now().plusDays(2).atTime(LocalTime.parse(clock, HHMM));
        } else if (tomorrow) {
            base = LocalDate.now().plusDays(1).atTime(LocalTime.parse(clock, HHMM));
        } else {
            base = LocalDate.now().atTime(LocalTime.parse(clock, HHMM));
            // 已过今天的时刻 → 顺延到明天（避免立刻误触发）
            if (base.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() <= now) {
                base = base.plusDays(1);
            }
        }
        return base.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** epoch 毫秒 → "MM-dd HH:mm" 显示用 */
    public static String formatEpoch(long epochMs) {
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault());
        return dt.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }
}
