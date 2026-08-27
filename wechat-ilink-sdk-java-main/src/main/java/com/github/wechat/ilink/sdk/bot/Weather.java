package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 天气查询类（基于 Open-Meteo 免费天气服务，无需 API Key）。
 *
 * <p>职责：根据城市名查询实时天气与未来 3 天预报，返回适合微信发送的中文文本。
 * 同时也承担"意图识别 + 城市提取"职责，外部只需调一次 {@link #tryHandle(String)}，
 * 本类会自动判断是否天气查询、提取城市、调用 API，外部不需要管正则。
 *
 * <p>支持中英文以外的多语言问法：德语 Wetter、法语 météo、日语 天気、韩语 날씨 等，
 * 城市名支持中文、拼音、英文、当地语言（如 Tokyo、Paris、Seoul、東京、서울）。
 *
 * <p>选型说明：wttr.in 在国内网络时通时断（实测多次超时），
 * Open-Meteo 响应稳定，两个接口都是公开免费、无需鉴权。
 */
public class Weather implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast";

    /** 天气意图：中文 + 多语言天气词（(?iu) = 忽略大小写 + Unicode 大小写折叠，兼容 "Wetter" 大写开头） */
    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "(?iu)天气|气温|温度|冷不冷|热不热|下雨|会不会下雪|台风|空气"
                    + "|weather|wetter|météo|meteo|날씨|天気|clima|température");

    /** WMO weather code → 中文描述 */
    private static final Map<Integer, String> WMO_ZH = new HashMap<>();

    /**
     * Open-Meteo geocoding 的 GeoNames 数据里中文地名覆盖不全（实测失败：扬州/温州/常州/泉州
     * /汕头/湛江/连云港/宿迁/泰州/荆州/通辽/汉中/湖州 等），中文搜不到时 fallback 到拼音再搜。
     * key=中文城市名，value=拼音（GeoNames 官方名）。
     */
    private static final Map<String, String> CITY_PINYIN = new HashMap<>();

    /** Open-Meteo geocoding 没有韩文地名（GeoNames 数据），把常见韩文城市名映射成英文/中文名 */
    private static final Map<String, String> KO_CITY_MAP = new HashMap<>();

    static {
        CITY_PINYIN.put("常州", "Changzhou");
        CITY_PINYIN.put("连云港", "Lianyungang");
        CITY_PINYIN.put("宿迁", "Suqian");
        CITY_PINYIN.put("泰州", "Taizhou");
        CITY_PINYIN.put("扬州", "Yangzhou");
        CITY_PINYIN.put("温州", "Wenzhou");
        CITY_PINYIN.put("荆州", "Jingzhou");
        CITY_PINYIN.put("通辽", "Tongliao");
        CITY_PINYIN.put("汉中", "Hanzhong");
        CITY_PINYIN.put("湖州", "Huzhou");
        CITY_PINYIN.put("泉州", "Quanzhou");
        CITY_PINYIN.put("湛江", "Zhanjiang");
        CITY_PINYIN.put("汕头", "Shantou");
        CITY_PINYIN.put("无锡", "Wuxi");
        CITY_PINYIN.put("镇江", "Zhenjiang");
        CITY_PINYIN.put("南通", "Nantong");
        CITY_PINYIN.put("徐州", "Xuzhou");
        CITY_PINYIN.put("盐城", "Yancheng");
        CITY_PINYIN.put("淮安", "Huaian");
        CITY_PINYIN.put("嘉兴", "Jiaxing");
        CITY_PINYIN.put("金华", "Jinhua");
        CITY_PINYIN.put("台州", "Taizhou");
        CITY_PINYIN.put("洛阳", "Luoyang");
        CITY_PINYIN.put("开封", "Kaifeng");
        CITY_PINYIN.put("襄阳", "Xiangyang");
        CITY_PINYIN.put("宜昌", "Yichang");
        CITY_PINYIN.put("桂林", "Guilin");
        CITY_PINYIN.put("柳州", "Liuzhou");
        CITY_PINYIN.put("北海", "Beihai");
        CITY_PINYIN.put("包头", "Baotou");
        CITY_PINYIN.put("赤峰", "Chifeng");
        CITY_PINYIN.put("延安", "Yanan");
        CITY_PINYIN.put("榆林", "Yulin");
        CITY_PINYIN.put("天水", "Tianshui");
        CITY_PINYIN.put("嘉峪关", "Jiayuguan");
        CITY_PINYIN.put("张掖", "Zhangye");
        CITY_PINYIN.put("银川", "Yinchuan");
        CITY_PINYIN.put("拉萨", "Lhasa");
        CITY_PINYIN.put("呼和浩特", "Hohhot");
        CITY_PINYIN.put("乌鲁木齐", "Urumqi");
        CITY_PINYIN.put("兰州", "Lanzhou");
        CITY_PINYIN.put("西宁", "Xining");
        CITY_PINYIN.put("大连", "Dalian");
        CITY_PINYIN.put("青岛", "Qingdao");
        CITY_PINYIN.put("烟台", "Yantai");
        CITY_PINYIN.put("威海", "Weihai");
        CITY_PINYIN.put("潍坊", "Weifang");
        CITY_PINYIN.put("宁波", "Ningbo");
        CITY_PINYIN.put("绍兴", "Shaoxing");
        CITY_PINYIN.put("芜湖", "Wuhu");
        CITY_PINYIN.put("惠州", "Huizhou");
        CITY_PINYIN.put("珠海", "Zhuhai");
        CITY_PINYIN.put("佛山", "Foshan");
        CITY_PINYIN.put("中山", "Zhongshan");
        CITY_PINYIN.put("烟台", "Yantai");
        CITY_PINYIN.put("三亚", "Sanya");
        CITY_PINYIN.put("洛阳", "Luoyang");
        CITY_PINYIN.put("兰州", "Lanzhou");

        KO_CITY_MAP.put("서울", "Seoul");
        KO_CITY_MAP.put("부산", "Busan");
        KO_CITY_MAP.put("인천", "Incheon");
        KO_CITY_MAP.put("대구", "Daegu");
        KO_CITY_MAP.put("광주", "Gwangju");
        KO_CITY_MAP.put("대전", "Daejeon");
        KO_CITY_MAP.put("울산", "Ulsan");
        KO_CITY_MAP.put("제주", "Jeju");

        WMO_ZH.put(0, "晴");
        WMO_ZH.put(1, "晴间多云");
        WMO_ZH.put(2, "多云");
        WMO_ZH.put(3, "阴");
        WMO_ZH.put(45, "雾");
        WMO_ZH.put(48, "冻雾");
        WMO_ZH.put(51, "毛毛雨");
        WMO_ZH.put(53, "毛毛雨");
        WMO_ZH.put(55, "毛毛雨");
        WMO_ZH.put(56, "冻毛毛雨");
        WMO_ZH.put(57, "冻毛毛雨");
        WMO_ZH.put(61, "小雨");
        WMO_ZH.put(63, "中雨");
        WMO_ZH.put(65, "大雨");
        WMO_ZH.put(66, "冻雨");
        WMO_ZH.put(67, "冻雨");
        WMO_ZH.put(71, "小雪");
        WMO_ZH.put(73, "中雪");
        WMO_ZH.put(75, "大雪");
        WMO_ZH.put(77, "雪粒");
        WMO_ZH.put(80, "阵雨");
        WMO_ZH.put(81, "阵雨");
        WMO_ZH.put(82, "强阵雨");
        WMO_ZH.put(85, "阵雪");
        WMO_ZH.put(86, "强阵雪");
        WMO_ZH.put(95, "雷阵雨");
        WMO_ZH.put(96, "雷阵雨伴冰雹");
        WMO_ZH.put(99, "雷阵雨伴冰雹");
    }

    private final OkHttpClient httpClient;
    private volatile String defaultCity;

    /**
     * @param defaultCity 用户消息里没带城市名时使用的默认城市
     */
    public Weather(String defaultCity) {
        this.defaultCity = (defaultCity == null || defaultCity.trim().isEmpty()) ? "深圳" : defaultCity.trim();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** 默认城市为深圳 */
    public Weather() {
        this("深圳");
    }

    /** 动态切换默认城市（用于"记住用户所在城市"，BotMain 在路由前调用） */
    public void setDefaultCity(String city) {
        if (city != null && !city.trim().isEmpty()) {
            this.defaultCity = city.trim();
        }
    }

    /**
     * 一站式处理：判断文本是否是天气查询，是就查并返回格式化结果，不是就返回 null。
     * 调用方写法：`String wx = weather.tryHandle(text); if (wx != null) { 回复; }`
     *
     * @param text 用户原始消息（文字或语音转出来的文字）
     * @return 天气文本（含 emoji、温度、预报），或 null（不是天气查询）
     */
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (!WEATHER_PATTERN.matcher(text).find()) return null;
        String city = extractCity(text);
        try {
            return queryWeather(city);
        } catch (Exception e) {
            return "抱歉，天气查询失败：" + e.getMessage();
        }
    }

    /** 判断是否天气查询（外部可以用来过滤） */
    public boolean isWeatherQuery(String text) {
        return text != null && !text.isEmpty() && WEATHER_PATTERN.matcher(text).find();
    }

    /** 从消息文本中提取城市名；提取不到时返回默认城市（中文+多语言两套逻辑） */
    public String extractCity(String text) {
        if (text == null || text.isEmpty()) return defaultCity;
        // 中文问法：找"天气"，取它前面的汉字段
        int idx = text.indexOf("天气");
        if (idx >= 0) {
            String before = text.substring(0, idx);
            String[] parts = before.split(
                    "[\\s,，。！？?]" + "|"
                    + "今天|明天|后天|现在|昨天|前天"
                    + "|这|那|这个|那个"
                    + "|查|查询|请问|请|帮|帮我|想|要|看|那个|问|询|的|了|吗|呢|呀|啊|嘛|吧");
            for (int i = parts.length - 1; i >= 0; i--) {
                String s = parts[i];
                if (s.length() < 2 || s.length() > 4) continue;
                String[] stop = {"今天", "明天", "后天", "现在", "昨天", "这", "那"};
                boolean bad = false;
                for (String x : stop) if (x.equals(s)) { bad = true; break; }
                if (bad) continue;
                return s;
            }
            return defaultCity;
        }
        // 外语问法：走外文城市提取（Wetter in Nanjing / météo à Paris / 東京の天気 / 서울 날씨）
        return extractForeignCity(text);
    }

    /**
     * 外文文本里提取城市候选（用于天气查询）。
     * 思路：按空格分词 → 去常见虚词/介词/天气词 → 剩下的词交给 Open-Meteo geocoding 自己匹配。
     * 同时支持无空格语言（日/韩）：剥掉尾部天气词和助词（"東京の天気" → "東京"）。
     */
    private String extractForeignCity(String text) {
        String[] stopWords = {
                // English
                "i", "in", "at", "on", "the", "a", "an", "of", "for", "to", "is", "are", "was", "were",
                "what", "whats", "how", "hows", "when", "where", "why", "who", "which",
                "today", "tomorrow", "now", "and", "like", "tell", "about", "give", "me",
                // Deutsch
                "ist", "der", "die", "das", "und", "wie", "war", "morgen", "heute", "luft",
                "luftqualität", "niederschlag", "grad", "in", "von", "zu", "mit", "den", "dem", "im",
                // Français
                "le", "la", "de", "du", "un", "et", "il", "el", "que", "en", "au", "aux", "ce", "quel", "quelle",
                "temps", "fait", "aujourd",
                // 日本語助词（平假名 + 罗马音）
                "の", "は", "を", "に", "で", "も", "から", "まで", "と", "へ", "や", "か", "が",
                "no", "ha", "wo", "ni", "de", "mo", "kara", "made", "to", "ga",
                // 中文混入的虚词
                "今天", "明天", "现在", "怎么", "样", "多少",
                // 天气关键词本身
                "天气", "weather", "wetter", "météo", "meteo", "날씨", "天気"
        };
        // 子串剥离专用：剥头/剥尾的短词（避免 "Paris" 被 "is" 误剥成 "Par"，
        // 所以这里不放英文介词，只放明确的多语言后缀词）
        String[] strippable = {
                // 天气关键词
                "天气", "weather", "wetter", "météo", "meteo", "날씨", "天気",
                // Deutsch
                "der", "die", "das", "ist", "und", "den", "dem", "morgen", "heute",
                // Français
                "le", "la", "du", "aux", "au",
                // 日本語助词
                "の", "は", "を", "に", "で", "も", "から", "まで", "と", "へ", "や", "か", "が"
        };

        List<String> keep = new ArrayList<>();
        for (String tok : text.split("\\s+")) {
            // 去掉标点，保留字母（中日韩法德俄字符都算 \p{L}）
            String clean = tok.replaceAll("[^\\p{L}]", "");
            if (clean.length() < 2 || clean.length() > 30) continue;
            // 停用词完全匹配
            boolean isStop = false;
            for (String sw : stopWords) {
                if (sw.equalsIgnoreCase(clean)) { isStop = true; break; }
            }
            if (isStop) continue;
            // 子串剥离：剥掉尾部/头部的天气词或助词（处理无空格语言如"東京の天気"），
            // 剥完剩 <2 个字符就不剥（避免误伤）
            boolean stripped = true;
            while (stripped && clean.length() > 1) {
                stripped = false;
                for (String sw : strippable) {
                    int rest = clean.length() - sw.length();
                    if (rest < 2) continue;
                    if (clean.endsWith(sw)) {
                        clean = clean.substring(0, rest);
                        stripped = true;
                        break;
                    }
                    if (clean.startsWith(sw)) {
                        clean = clean.substring(sw.length());
                        stripped = true;
                        break;
                    }
                }
            }
            if (clean.length() < 2) continue;
            keep.add(clean);
            if (keep.size() >= 3) break;  // 最多取 3 个词作为城市候选
        }
        if (keep.isEmpty()) return defaultCity;
        return String.join(" ", keep);
    }

    /**
     * 查询某城市天气，返回格式化后的中文天气文本。
     *
     * @param city 城市名（中文、拼音、英文或当地语言均可，如 "北京" / "beijing" / "Tokyo" / "東京"）
     * @return 天气文本；失败抛 IOException
     */
    public String queryWeather(String city) throws IOException {
        String cityName = city == null ? "" : city.trim();
        if (cityName.isEmpty()) {
            throw new IOException("城市名为空");
        }
        // 韩文城市名 → 英文名（GeoNames 没有韩文地名）
        if (KO_CITY_MAP.containsKey(cityName)) {
            cityName = KO_CITY_MAP.get(cityName);
        }

        // 1) 地理编码：城市名 → 经纬度。
        //    实测 Open-Meteo geocoding：中文/日文汉字名必须带 language=zh 才搜得到；
        //    英文名（Nanjing/Paris/Tokyo）带 language=zh 也能搜到，且返回名自动转成中文（南京/巴黎），
        //    回复显示更友好。所以统一带 language=zh。
        //    中文搜不到（GeoNames 覆盖不全，如扬州/温州）→ 查 CITY_PINYIN 用拼音再搜一次。
        String geoUrl = GEO_URL + "?name=" + URLEncoder.encode(cityName, StandardCharsets.UTF_8.name())
                + "&count=1&language=zh&format=json";
        JsonNode geoRoot = getJson(geoUrl);
        JsonNode result = geoRoot.path("results").path(0);
        if (result.isMissingNode()) {
            String pinyin = CITY_PINYIN.get(cityName);
            if (pinyin != null) {
                String geoUrl2 = GEO_URL + "?name=" + URLEncoder.encode(pinyin, StandardCharsets.UTF_8.name())
                        + "&count=1&language=zh&format=json";
                JsonNode geoRoot2 = getJson(geoUrl2);
                result = geoRoot2.path("results").path(0);
            }
        }
        if (result.isMissingNode()) {
            throw new IOException("没找到城市「" + cityName + "」，请检查城市名是否正确");
        }
        double lat = result.path("latitude").asDouble();
        double lon = result.path("longitude").asDouble();
        String displayName = result.path("name").asText(cityName);
        String region = result.path("admin1").asText("");
        String country = result.path("country").asText("");

        // 2) 天气数据：当前 + 未来 3 天
        String wxUrl = WEATHER_URL
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,apparent_temperature"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=Asia%2FShanghai&forecast_days=3";
        JsonNode wx = getJson(wxUrl);

        StringBuilder sb = new StringBuilder();
        String loc = displayName;
        if (region != null && !region.isEmpty() && !region.equals(displayName)) {
            loc += " " + region;
        }
        if (country != null && !country.isEmpty() && !country.equals(region)) {
            loc += " " + country;
        }
        sb.append("📍 ").append(loc.trim()).append("\n");

        JsonNode cur = wx.path("current");
        if (!cur.isMissingNode()) {
            double temp = cur.path("temperature_2m").asDouble(0);
            double feels = cur.path("apparent_temperature").asDouble(temp);
            double hum = cur.path("relative_humidity_2m").asDouble(0);
            double wind = cur.path("wind_speed_10m").asDouble(0);
            int code = cur.path("weather_code").asInt(-1);
            String desc = WMO_ZH.getOrDefault(code, "未知");

            sb.append("🌤 ").append(desc).append("，")
                    .append(round(temp)).append("°C")
                    .append("（体感 ").append(round(feels)).append("°C）\n");
            sb.append("💧 湿度 ").append(round(hum)).append("%　")
                    .append("💨 风速 ").append(round(wind)).append("km/h\n");
        }

        JsonNode daily = wx.path("daily");
        if (!daily.isMissingNode()) {
            JsonNode times = daily.path("time");
            JsonNode codes = daily.path("weather_code");
            JsonNode maxT = daily.path("temperature_2m_max");
            JsonNode minT = daily.path("temperature_2m_min");
            int count = Math.min(times.size(), 3);
            if (count > 0) {
                sb.append("\n📅 未来几天：\n");
                String[] labels = {"今天", "明天", "后天"};
                for (int i = 0; i < count; i++) {
                    String label = i < labels.length ? labels[i] : "第" + (i + 1) + "天";
                    String date = times.path(i).asText("");
                    if (date.length() >= 10) date = date.substring(5);
                    int c = codes.path(i).asInt(-1);
                    String desc = WMO_ZH.getOrDefault(c, "未知");
                    sb.append("  ").append(label).append("（").append(date).append("）：")
                            .append(desc).append("，")
                            .append(round(minT.path(i).asDouble(0))).append(" ~ ")
                            .append(round(maxT.path(i).asDouble(0))).append("°C\n");
                }
            }
        }

        return sb.toString().trim();
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "wechat-ilink-bot/1.0")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || body == null || body.trim().isEmpty()) {
                throw new IOException("天气服务请求失败: HTTP " + response.code());
            }
            return MAPPER.readTree(body);
        }
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
