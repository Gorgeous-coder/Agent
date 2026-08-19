package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
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
 * <p>选型说明：wttr.in 在国内网络时通时断（实测多次超时），
 * Open-Meteo 响应稳定，两个接口都是公开免费、无需鉴权。
 */
public class Weather implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast";

    /** 天气意图：消息里包含"天气/气温/冷不冷/热不热/下雨"等关键词 */
    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "天气|气温|温度|冷不冷|热不热|下雨|会不会下雪|台风|空气");

    /** WMO weather code → 中文描述 */
    private static final Map<Integer, String> WMO_ZH = new HashMap<>();

    static {
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
    private final String defaultCity;

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

    /** 从消息文本中提取城市名；提取不到时返回默认城市 */
    public String extractCity(String text) {
        if (text == null || text.isEmpty()) return defaultCity;
        // 1) 截掉"天气"及之后（indexOf 精确定位，避免正则回溯吞前缀）
        int idx = text.indexOf("天气");
        if (idx < 0) return defaultCity;
        String before = text.substring(0, idx);
        // 2) 把所有可能出现在"城市名前"或"城市名旁"的连接字/虚词全部作为分隔符，
        //    用 split 一次性切出汉字段，再倒序找 2-4 字的城市名
        String[] parts = before.split(
                "[\\s,，。！？?]" + "|"
                + "今天|明天|后天|现在|昨天|前天"
                + "|这|那|这个|那个"
                + "|查|查询|请问|请|帮|帮我|想|要|看|那个|问|询|的|了|吗|呢|呀|啊|嘛|吧");
        for (int i = parts.length - 1; i >= 0; i--) {
            String s = parts[i];
            if (s.length() < 2 || s.length() > 4) continue;
            // 防御：排除可能遗留的干扰词
            String[] stop = {"今天", "明天", "后天", "现在", "昨天", "这", "那"};
            boolean bad = false;
            for (String x : stop) if (x.equals(s)) { bad = true; break; }
            if (bad) continue;
            return s;
        }
        return defaultCity;
    }

    /**
     * 查询某城市天气，返回格式化后的中文天气文本。
     *
     * @param city 城市名（中文或拼音均可，如 "北京" / "beijing"）
     * @return 天气文本；失败抛 IOException
     */
    public String queryWeather(String city) throws IOException {
        String cityName = city == null ? "" : city.trim();
        if (cityName.isEmpty()) {
            throw new IOException("城市名为空");
        }

        // 1) 地理编码：城市名 → 经纬度
        String geoUrl = GEO_URL + "?name=" + URLEncoder.encode(cityName, StandardCharsets.UTF_8.name())
                + "&count=1&language=zh&format=json";
        JsonNode geoRoot = getJson(geoUrl);
        JsonNode result = geoRoot.path("results").path(0);
        if (result.isMissingNode()) {
            throw new IOException("没找到城市「" + cityName + "」，请检查城市名是否正确");
        }
        double lat = result.path("latitude").asDouble();
        double lon = result.path("longitude").asDouble();
        String displayName = result.path("name").asText(cityName);
        String region = result.path("admin1").asText("");

        // 2) 天气数据：当前 + 未来 3 天
        String wxUrl = WEATHER_URL
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,apparent_temperature"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=Asia%2FShanghai&forecast_days=3";
        JsonNode wx = getJson(wxUrl);

        StringBuilder sb = new StringBuilder();
        String loc = displayName + (region == null || region.isEmpty() ? "" : " " + region);
        sb.append("📍 ").append(loc).append("\n");

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
