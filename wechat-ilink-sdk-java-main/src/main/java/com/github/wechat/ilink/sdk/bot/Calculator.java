package com.github.wechat.ilink.sdk.bot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算工具类（纯 JDK 实现，不依赖外部引擎）。
 *
 * <p>职责：从用户消息里识别计算意图、提取算式（含中文运算符）、
 * 求值并返回结果。外部只需调一次 {@link #tryHandle(String)}，
 * 不是计算意图时返回 null。
 *
 * <p>支持的语法：
 * <ul>
 *   <li>四则运算：加、减、乘、除、小数、负数、括号</li>
 *   <li>中文运算符：加/减/乘/除/乘以/除以/×/÷</li>
 *   <li>幂与根：x 的 N 次方 / x 的平方 / x 的立方 / x 开方 / x 的平方根</li>
 * </ul>
 */
public class Calculator implements AutoCloseable {

    /** 算式字符匹配：数字/小数点/运算符/括号/Math函数名/^ */
    private static final Pattern EXPR_PATTERN = Pattern.compile(
            "[\\d.+\\-*/()\\s\\^Math,powsqrt(Math.Math,0123456789]+"
            + "|(?:Math\\.(?:sqrt|pow))");

    private final Parser parser = new Parser();

    /**
     * 一站式处理：判断文本是否是计算意图，是则计算并返回结果，否则返回 null。
     */
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!isMathQuery(text)) return null;

        String normalized = normalize(text);
        String expr = extract(normalized);
        if (expr == null || expr.isEmpty()) return null;

        try {
            double result = parser.parse(expr);
            return "🧮 " + expr + "\n= " + format(result);
        } catch (Exception e) {
            return "抱歉，计算失败：" + e.getMessage();
        }
    }

    /** 是否计算意图：含数字且含运算符/算术词 */
    public boolean isMathQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        if (!text.matches(".*\\d.*")) return false;
        return Pattern.compile("[+\\-*/×÷^]|(加|减|乘|除|乘以|除以|计算|算|等于|的平方|的立方|次方|开方|平方根|次幂|根号|√)").matcher(text).find();
    }

    /**
     * 把中文算术词转成可解析符号（长词优先，避免"除"先于"除以"匹配）：
     * 除以→/, ×/÷→乘除, 加减乘除→+-×÷,
     * x 的 N 次方→x^N, x 的平方→x^2, x 开方→x^0.5。
     */
    private String normalize(String text) {
        String s = text;
        // 全角括号
        s = s.replace('（', '(').replace('）', ')');
        // 长词优先
        s = s.replaceAll("除以", "/");
        s = s.replaceAll("乘以", "*");
        s = s.replaceAll("×", "*").replaceAll("÷", "/");
        s = s.replaceAll("加", "+").replaceAll("减", "-");
        s = s.replaceAll("乘", "*").replaceAll("除", "/");
        // x 的 N 次方 / 平方 / 立方（用 (?:\\s*的)+ 容忍连续多个"的"，以及支持中文数字"X 的三次方"）
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)(?:\\s*的)+\\s*平方\\s*根", "$1^0.5");
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)\\s*开方", "$1^0.5");
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)(?:\\s*的)+\\s*平方(?!\\s*根)", "$1^2");
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)(?:\\s*的)+\\s*立方(?!\\s*根)", "$1^3");
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)(?:\\s*的)+\\s*(\\d+(?:\\.\\d+)?)\\s*次方", "$1^$2");
        // 兼容"X 的中文数次方/平方/立方"（如"3 的三次方"、"2 的两次方"）
        String[][] cnMap = {{"一", "1"}, {"二", "2"}, {"三", "3"}, {"四", "4"}, {"五", "5"},
                            {"六", "6"}, {"七", "7"}, {"八", "8"}, {"九", "9"}, {"两", "2"}, {"十", "10"}};
        for (String[] m : cnMap) {
            s = s.replaceAll("(\\d+(?:\\.\\d+)?)(?:\\s*的)+\\s*" + m[0] + "\\s*(次方|平方|立方)", "$1^" + m[1]);
        }
        s = s.replaceAll("的?\\s*次方", "");
        s = s.replaceAll("的?\\s*次幂", "");
        // 根号 N / √N（之前漏了，导致"根号 25+3"被截成"25+3=28"而不是"√25+3=8"）
        // 直接转成幂运算 ^0.5，避开 parser 不支持函数调用的问题
        s = s.replaceAll("√\\s*(\\d+(?:\\.\\d+)?)", "$1^0.5");
        s = s.replaceAll("根号\\s*(\\d+(?:\\.\\d+)?)", "$1^0.5");
        // ^ 是幂运算符，下面 Parser 处理
        return s;
    }

    /** 从文本里提取算式段（数字+运算符的连续片段），去除中文 */
    private String extract(String normalized) {
        Matcher m = EXPR_PATTERN.matcher(normalized);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group());
        }
        return sb.toString().trim();
    }

    /** 格式化数字（整数去掉小数点后缀，小数保留） */
    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 递归下降算式求值器：支持四则、括号、负号、幂。
     * 入口：parseExpr → 加减；parseTerm → 乘除；parseFactor → 括号/数字/一元负号/幂。
     */
    static class Parser {
        private String s;
        private int pos;

        double parse(String expr) {
            this.s = expr;
            this.pos = 0;
            skipSpace();
            double v = parseExpr();
            skipSpace();
            if (pos != s.length()) {
                throw new RuntimeException("算式末尾有多余字符: '" + s.substring(pos) + "'");
            }
            return v;
        }

        private double parseExpr() {
            double v = parseTerm();
            while (pos < s.length()) {
                skipSpace();
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += parseTerm(); }
                else if (c == '-') { pos++; v -= parseTerm(); }
                else break;
            }
            return v;
        }

        private double parseTerm() {
            double v = parseFactor();
            while (pos < s.length()) {
                skipSpace();
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= parseFactor(); }
                else if (c == '/') {
                    pos++;
                    double d = parseFactor();
                    if (d == 0) throw new RuntimeException("除数不能为 0");
                    v /= d;
                }
                else break;
            }
            return v;
        }

        private double parseFactor() {
            skipSpace();
            if (pos >= s.length()) throw new RuntimeException("算式意外结束");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpr();
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new RuntimeException("缺少右括号");
                }
                pos++;
                return maybePower(v);
            }
            if (c == '-') { pos++; return -parseFactor(); }
            if (c == '+') { pos++; return parseFactor(); }
            return parseNumber();
        }

        private double parseNumber() {
            skipSpace();
            int start = pos;
            boolean dotSeen = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' && !dotSeen) { dotSeen = true; pos++; }
                else break;
            }
            if (start == pos) {
                throw new RuntimeException("期望数字，遇到: '" + (pos < s.length() ? s.charAt(pos) : "") + "'");
            }
            double v = Double.parseDouble(s.substring(start, pos));
            // 数字后可能跟 ^ 幂运算（如 "2^8"）
            return maybePower(v);
        }

        /** 处理 ^ 幂运算：x^y */
        private double maybePower(double base) {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                double exp = parseFactor();
                return Math.pow(base, exp);
            }
            return base;
        }

        private void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }

    @Override
    public void close() {
        // 无外部资源
    }
}