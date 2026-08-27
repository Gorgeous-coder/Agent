package com.github.wechat.ilink.sdk.bot;

import java.util.regex.Pattern;

/**
 * 健康知识建议 Skill（业务层，非 SDK 源码）。
 *
 * <p>用户问"健康建议 / 怎么养生 / 久坐 / 喝水 / 睡眠"等时，返回几条实用健康小贴士。
 * 纯模板生成（不调 LLM），响应快、零成本。不替代医生，文案里注明。
 */
public class HealthTipSkill implements Skill {

    private static final Pattern TIP_PATTERN = Pattern.compile(
            "健康建议|健康知识|养生|久坐|怎么喝水|喝多少水|护眼|睡眠建议|改善睡眠|饮食建议|运动建议|健康小贴士");

    @Override
    public String name() {
        return "健康建议";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!TIP_PATTERN.matcher(text).find()) return null;

        StringBuilder sb = new StringBuilder("💚 健康小贴士：\n");
        if (text.contains("久坐") || text.contains("上班")) {
            sb.append("  🪑 久坐：每坐 45-60 分钟起来活动 5 分钟，伸个懒腰、走几步\n");
            sb.append("  👀 护眼：屏幕前每 20 分钟远眺 20 秒，眨眼保持眼睛湿润\n");
        }
        if (text.contains("睡") || text.contains("失眠")) {
            sb.append("  🌙 睡眠：固定作息，睡前 1 小时远离手机，卧室保持黑暗安静\n");
            sb.append("  😴 成人建议每晚 7-8 小时睡眠\n");
        }
        if (text.contains("运动")) {
            sb.append("  🏃 运动：每周 150 分钟中等强度有氧（快走/慢跑/骑行），每次 30 分钟\n");
        }
        if (text.contains("饮食") || text.contains("吃")) {
            sb.append("  🥗 饮食：每天一斤蔬菜半斤水果，粗细粮搭配，少油少盐少糖\n");
        }
        if (text.contains("水")) {
            sb.append("  💧 喝水：每天 1500-2500ml（约 7-8 杯），少量多次，不要等口渴才喝\n");
        }
        if (sb.length() < 30) {
            sb.append("  💧 每天喝足 1.5-2.5L 水，多走动，少熬夜，均衡饮食\n");
        }
        sb.append("\n⚠️ 以上为一般性建议，如有不适请咨询专业医生");
        return sb.toString();
    }
}
