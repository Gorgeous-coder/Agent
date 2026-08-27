package com.github.wechat.ilink.sdk.bot;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天气简报 Skill（业务层，非 SDK 源码）。
 *
 * <p>与 {@link Weather} 的区别是：Weather 只给原始数据；本 Skill 在原始数据基础上
 * 加上"穿衣建议"和"出门是否带伞"等生活化建议，更贴近"今天该不该出门、该穿什么"的场景。
 *
 * <p>触发词：包含"天气简报"/"要不要带伞"/"穿什么"/"出门"等关键词时命中。
 * 数据来源复用 {@link Weather}（同一个 Open-Meteo 接口，不重复请求）。
 *
 * <p>建议生成采用纯规则（温度区间 + 天气码），不调用 LLM，保证响应快、成本低。
 */
public class WeatherBriefingSkill implements Skill {

    /** 触发词：含"简报/要不要带伞/穿什么/出门/该不该/出行"等 */
    private static final Pattern BRIEF_PATTERN = Pattern.compile(
            "天气简报|天气提醒|要不要带伞|要不要带雨|带不带伞|穿什么|穿啥|穿啥呀|穿哪件|该穿什么|出门|出行|该不该出门");

    /** 已知中国城市列表（避免把"今天出门要带伞"等虚词当城市名传给 geocoding） */
    private static final java.util.Set<String> KNOWN_CITIES = new java.util.HashSet<>(java.util.Arrays.asList(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "武汉", "成都", "重庆", "西安",
            "天津", "苏州", "郑州", "长沙", "东莞", "青岛", "沈阳", "宁波", "昆明", "大连",
            "厦门", "福州", "无锡", "济南", "合肥", "南昌", "南宁", "贵阳", "太原", "石家庄",
            "哈尔滨", "长春", "兰州", "西宁", "银川", "呼和浩特", "乌鲁木齐", "海口", "三亚",
            "拉萨", "香港", "澳门", "台北", "佛山", "中山", "惠州", "珠海", "唐山", "保定",
            "温州", "嘉兴", "金华", "台州", "南通", "镇江", "扬州", "徐州", "常州", "连云港",
            "盐城", "淮安", "宿迁", "洛阳", "开封", "新乡", "南阳", "许昌", "宜昌", "襄阳",
            "荆州", "黄冈", "黄石", "十堰", "株洲", "湘潭", "衡阳", "岳阳", "常德", "邵阳",
            "汕头", "潮州", "揭阳", "汕尾", "湛江", "茂名", "梅州", "韶关", "清远", "肇庆",
            "阳江", "云浮", "河源", "江门", "桂林", "柳州", "北海", "梧州", "钦州", "贵港",
            "玉林", "海口", "三亚", "琼海", "文昌", "万宁", "东方", "包头", "赤峰", "通辽",
            "鄂尔多斯", "呼伦贝尔", "巴彦淖尔", "乌海", "延安", "汉中", "榆林", "安康", "商洛",
            "铜川", "宝鸡", "咸阳", "渭南", "天水", "嘉峪关", "金昌", "武威", "张掖", "平凉",
            "酒泉", "庆阳", "定西", "陇南", "临夏", "甘南", "海东", "海北", "海南州", "黄南",
            "果洛", "玉树", "海西", "固原", "吴忠", "石嘴山", "中卫", "吐鲁番", "哈密", "阿克苏",
            "喀什", "和田", "伊犁", "塔城", "阿勒泰", "昌吉", "博尔塔拉", "巴音郭楞", "克孜勒苏",
            "五家渠", "阿拉尔", "图木舒克", "北屯", "铁门关", "双河", "可克达拉", "昆玉", "胡杨河"
    ));

    /** 不当作城市的虚词 */
    private static final java.util.Set<String> STOP_HAN = new java.util.HashSet<>(java.util.Arrays.asList(
            "今天", "明天", "后天", "现在", "昨天", "前天", "天气", "气温", "下雨", "下雪",
            "台风", "上班", "下班", "吃饭", "出门", "穿衣", "带伞", "建议", "怎么", "如何",
            "需要", "应该", "可以", "可能", "大概", "什么", "哪些", "这个", "那个", "周末",
            "下周", "本周", "早安", "晚安", "开心", "快乐", "健康", "身体", "老人", "小孩",
            "孩子", "朋友", "今天天气", "天天", "我", "你", "他", "她", "我们", "你们"
    ));

    /** 自定义时长提取（复用 UnitConverterSkill 思路，这里未用到） */
    private final Weather weather;
    /** 默认城市（来自配置文件 bot.default-city，可被"记住用户城市"动态切换） */
    private volatile String defaultCity;

    public WeatherBriefingSkill(Weather weather, String defaultCity) {
        this.weather = weather;
        this.defaultCity = defaultCity;
    }

    /** 动态切换默认城市（用于"记住用户所在城市"，BotMain 在路由前调用） */
    public void setDefaultCity(String city) {
        if (city != null && !city.trim().isEmpty()) {
            this.defaultCity = city.trim();
        }
    }

    /** 静态工具：从文本里找已知城市（供 BotMain"记住用户城市"复用） */
    public static String findKnownCity(String text) {
        if (text == null) return null;
        String[] cities = KNOWN_CITIES.toArray(new String[0]);
        java.util.Arrays.sort(cities, (a, b) -> Integer.compare(b.length(), a.length()));
        for (String c : cities) {
            if (text.contains(c)) return c;
        }
        return null;
    }

    @Override
    public String name() {
        return "天气简报";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!BRIEF_PATTERN.matcher(text).find()) return null;

        // 城市提取：先在 KNOWN_CITIES 找（避免把"今天出门要带伞"等虚词当城市），
        // 没有则用 defaultCity（不调用 weather.extractCity——它会把整句当城市导致乱码）
        String city = findKnownCity(text);
        if (city == null || city.isEmpty()) city = defaultCity;

        // 拿当前天气 + 未来 3 天
        String full;
        try {
            full = weather.queryWeather(city);
        } catch (IOException e) {
            return "⚠️ 天气服务调用失败：" + e.getMessage();
        }
        return buildBriefing(city, full);
    }

    /** 把天气原始数据 + 规则建议拼成简报 */
    private String buildBriefing(String city, String rawWeather) {
        if (rawWeather == null || rawWeather.isEmpty()) return "⚠️ 天气数据为空";

        // 解析温度、天气码、湿度、风速（从 queryWeather 输出里提取）
        Double temp = findFirstNumber(rawWeather, "°C");
        Double humidity = findFirstNumber(rawWeather, "%");
        Double wind = findFirstNumber(rawWeather, "km/h");

        // 找天气描述（"🌤 XX,..."或"今天：XX"）
        String desc = findFirstDescAfterEmoji(rawWeather);

        String clothing = clothingAdvice(temp);
        String umbrella = umbrellaAdvice(desc);
        String windTip = (wind != null && wind > 30)
                ? "\n💨 风速较大（" + wind + "km/h），注意防风"
                : "";
        String humidityTip = (humidity != null && humidity > 85)
                ? "\n💧 湿度偏高（" + humidity + "%），注意防潮"
                : "";

        return "📋 " + city + " 天气简报\n"
                + (desc != null ? "🌤 " + desc + "\n" : "")
                + (temp != null ? "🌡 气温 " + temp + "°C\n" : "")
                + (humidity != null ? "💧 湿度 " + humidity + "%\n" : "")
                + (wind != null ? "💨 风速 " + wind + "km/h\n" : "")
                + "\n"
                + "👔 " + clothing + "\n"
                + "☂️ " + umbrella
                + windTip
                + humidityTip;
    }

    /** 穿衣建议（按温度区间） */
    private static String clothingAdvice(Double temp) {
        if (temp == null) return "穿衣建议：根据气温增减衣物即可";
        if (temp < 0) return "穿衣建议：羽绒服 + 厚毛衣 + 秋裤 + 围巾手套，注意保暖防冻";
        if (temp < 10) return "穿衣建议：厚外套（呢子/棉服）+ 毛衣 + 长裤，怕冷可加秋裤";
        if (temp < 20) return "穿衣建议：夹克 / 风衣 + 薄毛衣 + 长裤，早晚温差大注意添衣";
        if (temp < 27) return "穿衣建议：长袖 T 恤 + 薄外套（早晚）+ 休闲长裤，舒适即可";
        if (temp < 32) return "穿衣建议：短袖 + 短裤 / 裙子，注意防晒和补水";
        return "穿衣建议：短袖 + 短裤，紫外线强，多喝水防中暑";
    }

    /** 带伞建议（按天气描述） */
    private static String umbrellaAdvice(String desc) {
        if (desc == null || desc.isEmpty()) return "带伞建议：关注最新天气预报";
        if (desc.contains("雨") || desc.contains("雪") || desc.contains("雷阵雨")) {
            return "带伞建议：☂️ 今天有降水，记得带伞";
        }
        if (desc.contains("雾")) {
            return "带伞建议：⛅ 暂不需要带伞，但有雾，开车注意安全";
        }
        return "带伞建议：☀️ 暂不需要带伞，可以放心出门";
    }

    /** 在文本里找 unit 前的第一个数字（含小数） */
    private static Double findFirstNumber(String text, String unit) {
        int idx = text.indexOf(unit);
        if (idx < 0) return null;
        // 向前找数字
        int end = idx;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isDigit(c) || c == '.' || c == '-') start--;
            else break;
        }
        if (start == end) return null;
        try {
            return Double.parseDouble(text.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 找 emoji 后的第一个非空描述片段（"🌤" 或 "今天：" 后面那段） */
    private static String findFirstDescAfterEmoji(String text) {
        // 优先匹配"🌤 XX,"
        int idx = text.indexOf("🌤");
        if (idx >= 0) {
            int comma = text.indexOf(',', idx);
            int newline = text.indexOf('\n', idx);
            int end = -1;
            for (int i = idx + 2; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ',' || c == '，' || c == '\n' || c == '(' || c == '（') {
                    end = i;
                    break;
                }
            }
            if (end > idx) return text.substring(idx + 2, end).trim();
        }
        // fallback：找第一行包含"今天"的描述
        for (String line : text.split("\n")) {
            if (line.contains("今天") || line.contains("明天")) {
                String s = line.replaceFirst(".*?[：:]", "").trim();
                if (!s.isEmpty() && s.length() < 30) return s;
            }
        }
        return null;
    }
}