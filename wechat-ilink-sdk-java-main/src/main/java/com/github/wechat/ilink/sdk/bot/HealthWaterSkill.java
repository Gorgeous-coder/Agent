package com.github.wechat.ilink.sdk.bot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健康喝水建议 Skill（业务层，非 SDK 源码）。
 *
 * <p>按《中国居民膳食指南》：成年人每天饮水约 1500-1700ml；更精确按体重 30-40ml/kg。
 * 用户可报体重："我60公斤该喝多少水" / "我体重 70kg"。支持按作息分布喝水时段。
 */
public class HealthWaterSkill implements Skill {

    private static final Pattern WATER_PATTERN = Pattern.compile(
            "喝多少水|喝水建议|喝水量|该喝多少|饮水量|每天喝|补水");
    private static final Pattern WEIGHT_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:公斤|千克|kg|KG)");

    private final RoutineSkill routine;

    public HealthWaterSkill(RoutineSkill routine) {
        this.routine = routine;
    }

    @Override
    public String name() {
        return "喝水建议";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!WATER_PATTERN.matcher(text).find()) return null;

        // 体重：有则按 30-40ml/kg 计算，无则按成人标准 1700ml
        double weight = 0;
        Matcher wm = WEIGHT_PATTERN.matcher(text);
        if (wm.find()) {
            try { weight = Double.parseDouble(wm.group(1)); } catch (NumberFormatException ignore) { }
        }
        int dailyMl = weight > 0 ? (int) Math.round(weight * 35) : 1700;
        int cups = (int) Math.ceil(dailyMl / 250.0);

        StringBuilder sb = new StringBuilder("💧 喝水建议：\n");
        if (weight > 0) {
            sb.append("  按体重 ").append((int) weight).append("kg × 35ml ≈ ").append(dailyMl).append("ml/天\n");
        } else {
            sb.append("  成人标准约 ").append(dailyMl).append("ml/天\n");
        }
        sb.append("  （约 ").append(cups).append(" 杯，每杯 250ml）\n\n");
        sb.append("  ⏰ 建议分布：\n");
        sb.append("    起床后 200ml\n");
        sb.append("    上午 400-500ml\n");
        sb.append("    下午 400-500ml\n");
        sb.append("    晚餐前 200ml\n");
        sb.append("    睡前 1 小时少量 100ml\n\n");
        sb.append("💡 少量多次，不要等口渴才喝；运动或天热时适当加量");
        return sb.toString();
    }
}
