package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美食推荐 Skill 家族（业务层，非 SDK 源码）。
 *
 * <p>一个类承载 3 个意图（dining / daily-dining / meal-plan）：
 * <ul>
 *   <li><b>美食推荐 dining</b>："附近有什么好吃的" / "上海有什么美食" → 高德周边搜餐厅</li>
 *   <li><b>午餐推荐 daily-dining</b>："中午吃什么" / "午餐推荐" → 高德周边搜快餐/简餐</li>
 *   <li><b>晚饭菜单 meal-plan</b>："今晚吃什么" / "买菜清单" → 模板生成菜单 + 食材清单（不调 API）</li>
 * </ul>
 *
 * <p>数据源：高德开放平台（地理编码 v3/geocode/geo + 周边搜索 v3/place/around），
 * 读取环境变量 {@code GAODE_KEY}（你本机已配置）。
 */
public class DiningSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String AROUND_URL = "https://restapi.amap.com/v3/place/around";

    /** 美食推荐触发词 */
    private static final Pattern DINING_PATTERN = Pattern.compile(
            "好吃的|美食|吃什么|餐厅推荐|推荐.*(?:餐厅|饭馆|馆子)|有什么吃的|哪家好吃|聚餐");
    /** 午餐触发词 */
    private static final Pattern LUNCH_PATTERN = Pattern.compile("中午|午饭|午餐|中饭");
    /** 晚饭/菜单触发词 */
    private static final Pattern DINNER_PATTERN = Pattern.compile("晚饭|晚餐|今晚吃|菜单|买菜|今晚");
    /** 城市提取："上海有什么好吃的" → 上海；"北京美食" → 北京 */
    private static final Pattern CITY_IN_TEXT = Pattern.compile(
            "(.{2,4}?)(?:有什么好吃的|有什么美食|美食推荐|餐厅推荐|哪家|吃饭|好吃)");

    /** 3 套家常菜单轮换（按星期） */
    private static final String[][] MENUS = {
            {"番茄炒蛋", "青椒肉丝", "紫菜蛋花汤"},
            {"可乐鸡翅", "清炒时蔬", "冬瓜丸子汤"},
            {"红烧排骨", "干煸豆角", "西红柿蛋汤"},
            {"宫保鸡丁", "凉拌黄瓜", "菠菜豆腐汤"},
            {"土豆炖牛肉", "蒜蓉西兰花", "海带排骨汤"},
            {"香煎鸡腿", "地三鲜", "金针菇蛋汤"},
            {"糖醋里脊", "蒜蓉油麦菜", "玉米排骨汤"}
    };
    /** 每道菜的主要食材（买菜清单用） */
    private static final String[][] MENU_INGREDIENTS = {
            {"番茄 2 个", "鸡蛋 3 个", "小葱 1 把"},
            {"青椒 2 个", "猪里脊 200g", "大蒜 3 瓣"},
            {"紫菜 1 包", "鸡蛋 1 个", "虾皮 1 小把"},
            {"鸡翅 8 个", "可乐 1 罐", "姜 1 块"},
            {"时令蔬菜 1 把", "蒜 3 瓣", "食用油适量"},
            {"冬瓜 300g", "肉末 100g", "姜 2 片"},
            {"排骨 500g", "红烧料包 1 个", "冰糖 10g"},
            {"豆角 300g", "猪肉末 150g", "干辣椒 5 个"},
            {"番茄 2 个", "鸡蛋 2 个", "小葱 1 把"},
            {"鸡胸肉 300g", "花生米 50g", "黄瓜 1 根"},
            {"黄瓜 1 根", "蒜 3 瓣", "芝麻油适量"},
            {"菠菜 1 把", "豆腐 1 块", "枸杞少许"},
            {"牛肉 400g", "土豆 2 个", "八角 2 个"},
            {"西兰花 1 颗", "蒜 3 瓣", "蚝油适量"},
            {"海带结 200g", "排骨 300g", "姜 2 片"},
            {"鸡腿 2 个", "生抽料酒适量", "黑胡椒少许"},
            {"茄子 1 根", "土豆 1 个", "青椒 1 个"},
            {"金针菇 200g", "鸡蛋 2 个", "葱 1 把"},
            {"里脊肉 300g", "糖醋汁 1 包", "淀粉适量"},
            {"油麦菜 1 把", "蒜 3 瓣", "耗油适量"},
            {"玉米 1 根", "排骨 400g", "胡萝卜 1 根"}
    };

    private final OkHttpClient httpClient;
    private final String amapKey;
    private final String defaultCity;
    private final Weather weather;

    public DiningSkill(String amapKey, String defaultCity, Weather weather) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.amapKey = amapKey == null || amapKey.isEmpty() ? System.getenv("GAODE_KEY") : amapKey;
        this.defaultCity = defaultCity;
        this.weather = weather;
    }

    @Override
    public String name() {
        return "美食推荐";
    }

    @Override
    public String tryHandle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (amapKey == null || amapKey.isEmpty()) {
            // 未配置高德 key：meal-plan 仍可用（纯模板），dining/lunch 提示配置
            if (DINNER_PATTERN.matcher(text).find()) return mealPlan();
            if (DINING_PATTERN.matcher(text).find() || LUNCH_PATTERN.matcher(text).find()) {
                return "⚠️ 未配置高德 API Key（环境变量 GAODE_KEY），美食推荐暂不可用";
            }
            return null;
        }
        // 优先级：晚饭/菜单 > 午餐 > 美食推荐（"中午吃什么"命中 lunch；"今晚吃什么"命中 dinner）
        if (DINNER_PATTERN.matcher(text).find()) return mealPlan();
        if (LUNCH_PATTERN.matcher(text).find()) return searchRestaurants(extractCity(text), "快餐 简餐");
        if (DINING_PATTERN.matcher(text).find()) return searchRestaurants(extractCity(text), null);
        return null;
    }

    /** 提取城市：优先"XX有什么好吃的"模式，其次 Weather.extractCity，兜底默认城市 */
    private String extractCity(String text) {
        Matcher m = CITY_IN_TEXT.matcher(text);
        if (m.find()) {
            String c = m.group(1).trim();
            if (c.length() >= 2 && c.length() <= 4 && !c.contains("的")) {
                return c;
            }
        }
        if (text.contains("天气")) {
            String c = weather.extractCity(text);
            if (c != null && !c.isEmpty()) return c;
        }
        return defaultCity;
    }

    /**
     * 高德周边搜索餐厅。
     *
     * @param city    城市名
     * @param keyword 美食关键词（null 或空则搜"餐厅"）
     * @return 格式化推荐文本；失败返回错误提示
     */
    private String searchRestaurants(String city, String keyword) {
        try {
            String location = geocode(city);
            if (location == null) {
                return "⚠️ 没找到城市「" + city + "」，换个城市试试";
            }
            String kw = (keyword == null || keyword.trim().isEmpty()) ? "餐厅" : keyword.trim();
            String url = AROUND_URL
                    + "?key=" + amapKey
                    + "&location=" + location
                    + "&keywords=" + URLEncoder.encode(kw, StandardCharsets.UTF_8.name())
                    + "&types=050000"
                    + "&radius=3000"
                    + "&offset=5&page=1&extensions=base";
            JsonNode root = getJson(url);
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.size() == 0) {
                return "😅 " + city + " 附近 3 公里内没搜到" + kw + "，换个关键词试试";
            }

            StringBuilder sb = new StringBuilder("🍽 " + city + " 附近美食（" + kw + "）：\n");
            int n = 0;
            for (JsonNode poi : pois) {
                if (n >= 5) break;
                String name = poi.path("name").asText("?");
                String distance = poi.path("distance").asText("");
                String address = poi.path("address").asText("");
                String tel = poi.path("tel").asText("");
                sb.append("  ").append(++n).append(". ").append(name);
                if (!distance.isEmpty()) {
                    int d = (int) Math.round(Double.parseDouble(distance));
                    sb.append("（").append(d).append("m）");
                }
                sb.append("\n");
                if (!address.isEmpty()) sb.append("      📍 ").append(address).append("\n");
                if (!tel.isEmpty()) sb.append("      ☎️ ").append(tel).append("\n");
            }
            sb.append("\n💡 说'再找找'可刷新，'中午吃什么'推荐午餐");
            return sb.toString().trim();
        } catch (Exception e) {
            return "⚠️ 美食推荐失败：" + e.getMessage();
        }
    }

    /** 城市名 → 经纬度（"lon,lat"）；失败返回 null */
    private String geocode(String city) throws IOException {
        String url = GEO_URL + "?key=" + amapKey
                + "&address=" + URLEncoder.encode(city, StandardCharsets.UTF_8.name());
        JsonNode root = getJson(url);
        JsonNode geocodes = root.path("geocodes");
        if (!geocodes.isArray() || geocodes.size() == 0) return null;
        return geocodes.get(0).path("location").asText("");
    }

    /** 晚饭菜单 + 买菜清单（模板按星期轮换，不调 API） */
    private String mealPlan() {
        int idx = (LocalDate.now().getDayOfWeek().getValue() - 1) % MENUS.length;
        String[] dishes = MENUS[idx];
        StringBuilder sb = new StringBuilder("🍳 今晚菜单（周" + "一二三四五六日".charAt(idx) + "）：\n");
        for (String d : dishes) {
            sb.append("  🥘 ").append(d).append("\n");
        }
        sb.append("\n🛒 买菜清单：\n");
        for (int i = 0; i < dishes.length; i++) {
            String[] ing = MENU_INGREDIENTS[idx * 3 + i];
            sb.append("  • ").append(dishes[i]).append("：");
            for (int j = 0; j < ing.length; j++) {
                sb.append(ing[j]);
                if (j < ing.length - 1) sb.append("、");
            }
            sb.append("\n");
        }
        sb.append("\n💡 明天想吃别的？明天菜单会自动换新");
        return sb.toString().trim();
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || body == null || body.trim().isEmpty()) {
                throw new IOException("高德请求失败: HTTP " + response.code());
            }
            return MAPPER.readTree(body);
        }
    }
}
