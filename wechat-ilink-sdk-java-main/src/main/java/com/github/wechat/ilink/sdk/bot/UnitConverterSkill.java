package com.github.wechat.ilink.sdk.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单位换算 Skill（业务层，非 SDK 源码）。
 *
 * <p>支持两类换算：
 * <ul>
 *   <li>长度：米 / 千米(公里) / 厘米 / 毫米 / 英尺 / 英寸 / 英里 / 码</li>
 *   <li>重量：克 / 千克(公斤) / 吨 / 斤 / 磅 / 盎司</li>
 * </ul>
 *
 * <p>问法示例：
 * <ul>
 *   <li>"5千米等于多少米"</li>
 *   <li>"3.5公斤是多少斤"</li>
 *   <li>"10斤等于多少千克"</li>
 *   <li>"1英里等于多少公里"</li>
 *   <li>"100厘米换多少米"</li>
 * </ul>
 *
 * <p>命中返回 "📏 5千米 = 5000米" / "⚖️ 3.5公斤 = 7斤" 这类文本；
 * 非换算问法返回 {@code null}（由路由继续走 RAG / LLM）。
 */
public class UnitConverterSkill implements Skill {

    /** 长度单位 → 米 */
    private static final Map<String, Double> LENGTH_UNITS = new HashMap<>();
    /** 重量单位 → 克 */
    private static final Map<String, Double> WEIGHT_UNITS = new HashMap<>();

    static {
        // 长度（基准：米）
        LENGTH_UNITS.put("米", 1.0);
        LENGTH_UNITS.put("m", 1.0);
        LENGTH_UNITS.put("千米", 1000.0);
        LENGTH_UNITS.put("公里", 1000.0);
        LENGTH_UNITS.put("km", 1000.0);
        LENGTH_UNITS.put("厘米", 0.01);
        LENGTH_UNITS.put("cm", 0.01);
        LENGTH_UNITS.put("毫米", 0.001);
        LENGTH_UNITS.put("mm", 0.001);
        LENGTH_UNITS.put("英尺", 0.3048);
        LENGTH_UNITS.put("ft", 0.3048);
        LENGTH_UNITS.put("英寸", 0.0254);
        LENGTH_UNITS.put("in", 0.0254);
        LENGTH_UNITS.put("英里", 1609.344);
        LENGTH_UNITS.put("mi", 1609.344);
        LENGTH_UNITS.put("码", 0.9144);
        LENGTH_UNITS.put("yd", 0.9144);

        // 重量（基准：克）
        WEIGHT_UNITS.put("克", 1.0);
        WEIGHT_UNITS.put("g", 1.0);
        WEIGHT_UNITS.put("千克", 1000.0);
        WEIGHT_UNITS.put("公斤", 1000.0);
        WEIGHT_UNITS.put("kg", 1000.0);
        WEIGHT_UNITS.put("吨", 1_000_000.0);
        WEIGHT_UNITS.put("t", 1_000_000.0);
        WEIGHT_UNITS.put("斤", 500.0);
        WEIGHT_UNITS.put("磅", 453.59237);
        WEIGHT_UNITS.put("lb", 453.59237);
        WEIGHT_UNITS.put("盎司", 28.3495);
        WEIGHT_UNITS.put("oz", 28.3495);
    }

    /** "数字 + 源单位 + 连接词 + 目标单位"，如 "5千米等于多少米"、"3.5公斤是多少斤"、"100厘米换多少米" */
    private static final Pattern CONVERT_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*([\\u4e00-\\u9fa5A-Za-z]{1,4}?)\\s*"
                    + "(?:等于|转换成|换成|换算成|折合|是|换|＝|=)\\s*(?:多少|几)?\\s*"
                    + "([\\u4e00-\\u9fa5A-Za-z]{1,4})");

    /** 单位词（用于意图识别，避免对纯计算/闲聊文本误触发） */
    private static final Pattern UNIT_WORD_PATTERN = Pattern.compile(
            "米|千米|公里|厘米|毫米|英尺|英寸|英里|码|"
                    + "克|千克|公斤|吨|斤|磅|盎司|"
                    + "\\bkm\\b|\\bcm\\b|\\bmm\\b|\\bft\\b|\\bin\\b|\\bmi\\b|\\bkg\\b|\\bg\\b|\\blb\\b|\\boz\\b");

    @Override
    public String name() {
        return "单位换算";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        // 意图前置判断：必须含换算连接词 + 单位词，避免误触发
        boolean hasVerb = text.contains("等于") || text.contains("换成")
                || text.contains("转换成") || text.contains("换算成")
                || text.contains("折合") || text.contains("是")
                || text.contains("换") || text.contains("=") || text.contains("＝");
        if (!hasVerb) return null;
        if (!UNIT_WORD_PATTERN.matcher(text).find()) return null;

        Matcher m = CONVERT_PATTERN.matcher(text);
        if (!m.find()) return null;

        double value;
        try {
            value = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String srcUnit = m.group(2);
        String tgtUnit = m.group(3);

        // 判断类别并换算（都以基准单位换算：长度→米，重量→克）
        Double srcLen = LENGTH_UNITS.get(srcUnit);
        Double tgtLen = LENGTH_UNITS.get(tgtUnit);
        if (srcLen != null && tgtLen != null) {
            double result = value * srcLen / tgtLen;
            return "📏 " + format(value) + srcUnit + " = " + format(result) + tgtUnit;
        }

        Double srcWt = WEIGHT_UNITS.get(srcUnit);
        Double tgtWt = WEIGHT_UNITS.get(tgtUnit);
        if (srcWt != null && tgtWt != null) {
            double result = value * srcWt / tgtWt;
            return "⚖️ " + format(value) + srcUnit + " = " + format(result) + tgtUnit;
        }

        // 有换算意图但单位不在支持列表 → 给出友好提示（算命中，避免继续走 LLM 瞎答）
        return "⚠️ 目前只支持长度和重量换算。长度：米/千米/公里/厘米/毫米/英尺/英寸/英里/码；重量：克/千克/公斤/吨/斤/磅/盎司。";
    }

    /** 数值格式化：整数不带小数；小数最多 4 位并去掉末尾 0 */
    private static String format(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format("%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
