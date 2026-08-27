package com.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.dto.*;
import com.llm.service.LlmService;
import com.location.service.AmapLocationService;
import com.websearch.service.WebSearchService;
import com.websearch.dto.WebSearchResult;
import com.location.dto.GeocodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TravelPlannerAgent {
    private String currentDestination = "";
    private final LlmService llmService;
    private final WebSearchService webSearchService;
    private final AmapLocationService amapLocationService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelPlannerAgent(
            LlmService llmService,
            WebSearchService webSearchService,
            AmapLocationService amapLocationService,
            @Qualifier("deepseekClient") ChatClient chatClient
    ) {
        this.llmService = llmService;
        this.webSearchService = webSearchService;
        this.amapLocationService = amapLocationService;
        this.chatClient = chatClient;
    }

    /**
     * 核心方法：根据用户需求生成完整行程计划
     */
    public TravelPlanData generatePlan(String destination, int days, String preferences) {
        this.currentDestination = destination;
        log.info("[TravelPlanner] 开始生成行程: destination={}, days={}, preferences={}",
                destination, days, preferences);

        try {
            // Step 1: 联网搜索旅游攻略
            List<WebSearchResult> searchResults = searchTravelGuides(destination, days);
            String searchContext = formatSearchResults(searchResults);

            // Step 2: LLM 生成结构化行程
            String llmOutput = callLlmToGenerateItinerary(destination, days, preferences, searchContext);
            log.info("[TravelPlanner] LLM 输出: {}", llmOutput);

            // Step 3: 解析 LLM 输出的 JSON
            RawItinerary rawItinerary = parseLlmOutput(llmOutput);

            // Step 4: 地理编码（景点名 → 经纬度）
            List<String> allPlaceNames = rawItinerary.getAllPlaceNames();
            Map<String, GeocodeResult> geocodeMap = batchGeocode(allPlaceNames);

            // Step 5: 构建 TravelPlanData
            TravelPlanData planData = buildTravelPlan(rawItinerary, geocodeMap, destination);

            // Step 6: 计算路线
            planData = calculateRoutes(planData, rawItinerary);

            // Step 7: 计算地图视野和提示
            planData.setViewport(calculateViewport(planData));
            planData.setCityCenter(getCityCenter(destination));
            planData.setTips(rawItinerary.getTips() != null ? rawItinerary.getTips() : generateDefaultTips(destination));

            log.info("[TravelPlanner] 行程生成完成: {} 天", planData.getDays().size());
            return planData;

        } catch (Exception e) {
            log.error("[TravelPlanner] 生成失败: {}", e.getMessage(), e);
            return generateFallbackPlan(destination, days);
        }
    }

    /**
     * Step 1: 联网搜索旅游攻略
     */
    private List<WebSearchResult> searchTravelGuides(String destination, int days) {
        // ⭐ 暂时禁用联网搜索，避免超时
        log.info("[TravelPlanner] 使用离线模式（搜索已禁用）");
        return new ArrayList<>();

        // 如果以后想启用搜索，用下面这行：
        // String query = String.format("%s %d日游 旅游攻略 景点推荐 行程安排", destination, days);
        // try {
        //     return webSearchService.search(query, 10);
        // } catch (Exception e) {
        //     log.warn("[TravelPlanner] 搜索失败: {}", e.getMessage());
        //     return new ArrayList<>();
        // }
    }

    /**
     * Step 2: 调用 LLM 生成结构化行程
     */
    private String callLlmToGenerateItinerary(String destination, int days, String preferences, String searchContext) {
        String systemPrompt = getDefaultSystemPrompt();
        String userPrompt = String.format(
                "目的地：%s\n天数：%d天\n偏好：%s\n\n搜索到的旅游攻略信息：\n%s\n\n请严格按照 JSON 格式输出行程计划。",
                destination, days,
                preferences != null ? preferences : "综合推荐",
                searchContext != null && !searchContext.isEmpty() ? searchContext : "无搜索结果，请根据你的知识生成"
        );

        try {
            String result = llmService.chat(
                    userPrompt,
                    null,
                    chatClient,
                    "travel-planner",
                    systemPrompt,
                    false
            );
            return result;
        } catch (Exception e) {
            log.warn("[TravelPlanner] LLM 调用失败: {}", e.getMessage());
            return generateDefaultItineraryJson(destination, days);
        }
    }

    private String getDefaultSystemPrompt() {
        return """
                你是一个专业的旅游规划专家。根据用户提供的旅游攻略信息，生成详细的行程计划。
                必须严格按照 JSON 格式输出，不要有任何额外文字：
                {
                  "city": "城市名",
                  "totalDays": 3,
                  "days": [
                    {
                      "day": 1,
                      "theme": "主题",
                      "places": [
                        {
                          "name": "景点名称",
                          "type": "景点",
                          "description": "详细描述，含推荐理由、门票、注意事项",
                          "duration": "2小时",
                          "transportMode": "步行/公交/地铁/打车"
                        }
                      ]
                    }
                  ],
                  "tips": ["旅行小贴士1", "旅行小贴士2"]
                }
                """;
    }

    /**
     * Step 3: 解析 LLM 输出
     */
    private RawItinerary parseLlmOutput(String llmOutput) {
        try {
            String json = extractJson(llmOutput);
            log.info("[TravelPlanner] 解析 JSON: {}", json);
            return objectMapper.readValue(json, RawItinerary.class);
        } catch (Exception e) {
            log.error("[TravelPlanner] 解析 LLM 输出失败: {}", e.getMessage());
            return extractFromText(llmOutput);
        }
    }

    /**
     * 从文本中提取景点（降级方案）
     */
    private RawItinerary extractFromText(String text) {
        RawItinerary raw = new RawItinerary();
        raw.days = new ArrayList<>();
        raw.tips = Arrays.asList("建议提前规划", "注意天气变化");

        List<String> spots = new ArrayList<>();
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.contains("中山陵") || line.contains("总统府") || line.contains("夫子庙") ||
                    line.contains("玄武湖") || line.contains("博物院") || line.contains("明孝陵") ||
                    line.contains("牛首山") || line.contains("大报恩寺") || line.contains("瞻园") ||
                    line.contains("老门东") || line.contains("科举")) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("([\\u4e00-\\u9fa5]{2,10}[景区园馆陵庙寺楼]?)");
                java.util.regex.Matcher m = p.matcher(line);
                while (m.find()) {
                    String name = m.group(1);
                    if (name.length() >= 2 && !spots.contains(name)) {
                        spots.add(name);
                    }
                }
            }
        }

        if (spots.isEmpty()) {
            spots = Arrays.asList("中山陵", "总统府", "夫子庙", "玄武湖");
        }

        RawDay day = new RawDay();
        day.day = 1;
        day.theme = "经典游览";
        day.places = new ArrayList<>();
        for (String name : spots) {
            RawPlace p = new RawPlace();
            p.name = name;
            p.type = "景点";
            p.description = "南京著名景点";
            p.duration = "2小时";
            p.transportMode = "步行";
            day.places.add(p);
        }
        raw.days.add(day);
        raw.city = "南京";

        return raw;
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * Step 4: 批量地理编码
     */
    private Map<String, GeocodeResult> batchGeocode(List<String> placeNames) {
        Map<String, GeocodeResult> resultMap = new HashMap<>();
        int delayMs = 300;

        for (String originalName : placeNames) {
            if (originalName == null || originalName.isBlank()) continue;

            // ⭐ 1. 清理名称（和 buildTravelPlan 里保持一致）
            String cleanName = originalName
                    .replaceAll("[（(].*[）)]", "")
                    .replaceAll("、.*", "")
                    .replaceAll("画舫$|步行街$|商圈$|景区$|风景名胜区$|旅游景区$|旅游区$|风景区$|遗址$|博物馆$|纪念馆$|公园$", "")
                    .trim();

            // 跳过非景点
            if (cleanName.contains("返程") || cleanName.contains("出发") ||
                    cleanName.contains("回程") || cleanName.contains("准备")) {
                log.info("[TravelPlanner] 跳过非景点: {}", originalName);
                continue;
            }

            // ⭐ 2. 手动映射（和 buildTravelPlan 里保持一致）
            if (cleanName.contains("总统府")) cleanName = "南京总统府";
            else if (cleanName.contains("夫子庙") && !cleanName.contains("秦淮")) cleanName = "夫子庙";
            else if (cleanName.contains("中山陵")) cleanName = "中山陵";
            else if (cleanName.contains("明孝陵")) cleanName = "明孝陵";
            else if (cleanName.contains("玄武湖")) cleanName = "玄武湖";
            else if (cleanName.contains("南京博物院") || cleanName.contains("博物院")) cleanName = "南京博物院";
            else if (cleanName.contains("牛首山")) cleanName = "牛首山文化旅游区";
            else if (cleanName.contains("大报恩寺")) cleanName = "大报恩寺遗址景区";
            else if (cleanName.contains("南京城墙") || cleanName.contains("城墙")) cleanName = "南京城墙";
            else if (cleanName.contains("六朝博物馆")) cleanName = "六朝博物馆";
            else if (cleanName.contains("瞻园")) cleanName = "瞻园";
            else if (cleanName.contains("新街口")) cleanName = "新街口";

            log.info("[TravelPlanner] batchGeocode 清理: {} -> {}", originalName, cleanName);

            try {
                GeocodeResult result = null;

                // ⭐ 3. 先尝试地理编码
                try {
                    result = amapLocationService.geocode(cleanName);
                    if (result != null && result.longitude() != 0) {
                        log.info("[TravelPlanner] ✅ 地理编码成功: {} -> ({}, {})",
                                cleanName, result.longitude(), result.latitude());
                    }
                } catch (Exception e) {
                    log.debug("[TravelPlanner] 地理编码失败: {}", cleanName);
                }

                // ⭐ 4. 如果失败，尝试关键字搜索
                if (result == null || result.longitude() == 0) {
                    try {
                        String city = currentDestination != null ? currentDestination : "全国";
                        result = amapLocationService.searchPlace(cleanName, city);
                        if (result != null && result.longitude() != 0) {
                            log.info("[TravelPlanner] ✅ 关键字搜索成功: {} -> ({}, {})",
                                    cleanName, result.longitude(), result.latitude());
                        }
                    } catch (Exception e) {
                        log.debug("[TravelPlanner] 关键字搜索失败: {}", cleanName);
                    }
                }

                // ⭐ 5. 如果成功，存入结果（用原始名称作为 key）
                if (result != null && result.longitude() != 0) {
                    resultMap.put(originalName, result);
                } else {
                    log.warn("[TravelPlanner] ❌ 匹配失败: {} -> {}", originalName, cleanName);
                    // 给默认坐标，防止地图空白
                    double defaultLng = 118.78 + (Math.random() - 0.5) * 0.2;
                    double defaultLat = 32.06 + (Math.random() - 0.5) * 0.15;
                    GeocodeResult defaultResult = new GeocodeResult(
                            originalName, "", "", "",
                            defaultLng, defaultLat
                    );
                    resultMap.put(originalName, defaultResult);
                }

                Thread.sleep(delayMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[TravelPlanner] 处理失败: {} - {}", originalName, e.getMessage());
            }
        }
        return resultMap;
    }

    /**
     * Step 5: 构建 TravelPlanData
     */
    private TravelPlanData buildTravelPlan(RawItinerary raw, Map<String, GeocodeResult> geocodeMap, String destination) {
        List<TravelDay> days = new ArrayList<>();

        String city = raw.getCity() != null ? raw.getCity() : destination;
        List<RawDay> rawDays = raw.getDays();

        if (rawDays == null || rawDays.isEmpty()) {
            log.warn("[TravelPlanner] 没有行程数据，使用降级方案");
            return generateFallbackPlan(destination, 3);
        }

        for (RawDay rawDay : rawDays) {
            List<RawPlace> rawPlaces = rawDay.getPlaces();
            List<TravelPlace> places = new ArrayList<>();

            if (rawPlaces != null) {
                for (RawPlace rawPlace : rawPlaces) {
                    if (rawPlace.name == null || rawPlace.name.isBlank()) continue;

                    // ⭐ 清理名称
                    String cleanName = rawPlace.name
                            .replaceAll("[（(].*[）)]", "")
                            .replaceAll("、.*", "")
                            .replaceAll("画舫$|步行街$|商圈$|景区$|风景名胜区$|旅游景区$|旅游区$|风景区$|遗址$|博物馆$|纪念馆$|公园$", "")
                            .trim();

                    // ⭐ 跳过非景点
                    if (cleanName.contains("返程") || cleanName.contains("出发") || cleanName.contains("回程") || cleanName.contains("准备")) {
                        log.info("[TravelPlanner] 跳过非景点: {}", rawPlace.name);
                        continue;
                    }

                    // ⭐ 手动映射
                    if (cleanName.contains("总统府")) cleanName = "南京总统府";
                    else if (cleanName.contains("夫子庙") && !cleanName.contains("秦淮")) cleanName = "夫子庙";
                    else if (cleanName.contains("中山陵")) cleanName = "中山陵";
                    else if (cleanName.contains("明孝陵")) cleanName = "明孝陵";
                    else if (cleanName.contains("玄武湖")) cleanName = "玄武湖";
                    else if (cleanName.contains("南京博物院") || cleanName.contains("博物院")) cleanName = "南京博物院";
                    else if (cleanName.contains("牛首山")) cleanName = "牛首山文化旅游区";
                    else if (cleanName.contains("大报恩寺")) cleanName = "大报恩寺遗址景区";
                    else if (cleanName.contains("南京城墙") || cleanName.contains("城墙")) cleanName = "南京城墙";
                    else if (cleanName.contains("六朝博物馆")) cleanName = "六朝博物馆";
                    else if (cleanName.contains("瞻园")) cleanName = "瞻园";
                    else if (cleanName.contains("新街口")) cleanName = "新街口";

                    log.info("[TravelPlanner] 清理名称: {} -> {}", rawPlace.name, cleanName);

                    // ⭐ 从地理编码结果中获取经纬度
                    GeocodeResult geo = geocodeMap.get(cleanName);
                    // 模糊匹配
                    if (geo == null) {
                        for (Map.Entry<String, GeocodeResult> entry : geocodeMap.entrySet()) {
                            if (entry.getKey().contains(cleanName) || cleanName.contains(entry.getKey())) {
                                geo = entry.getValue();
                                log.info("[TravelPlanner] 模糊匹配成功: {} -> {}", cleanName, entry.getKey());
                                break;
                            }
                        }
                    }

                    double lng = geo != null ? geo.longitude() : 0.0;
                    double lat = geo != null ? geo.latitude() : 0.0;

                    TravelPlace place = TravelPlace.builder()
                            .name(rawPlace.name)  // 保留原始名称显示
                            .type(rawPlace.type != null ? rawPlace.type : "景点")
                            .description(rawPlace.description != null ? rawPlace.description : "")
                            .duration(rawPlace.duration != null ? rawPlace.duration : "2小时")
                            .longitude(lng)
                            .latitude(lat)
                            .build();
                    places.add(place);
                    log.info("[TravelPlanner] 景点: {}, 经纬度: {}, {}", place.getName(), lng, lat);
                }
            }

            // ⭐ 如果当天没有有效景点，跳过
            if (places.isEmpty()) {
                log.warn("[TravelPlanner] 第 {} 天没有有效景点，跳过", rawDay.getDay());
                continue;
            }

            // ⭐ 创建 TravelDay
            TravelDay day = TravelDay.builder()
                    .day(rawDay.getDay() > 0 ? rawDay.getDay() : days.size() + 1)
                    .weather("")
                    .places(places)
                    .summary(rawDay.getTheme() != null ? rawDay.getTheme() : "第" + (days.size() + 1) + "天")
                    .build();
            days.add(day);
        }

        // ⭐ 返回结果
        return TravelPlanData.builder()
                .destination(city)
                .days(days)
                .tips(raw.getTips() != null ? raw.getTips() : new ArrayList<>())
                .build();
    }

    /**
     * Step 6: 计算景点之间的路线
     */
    private TravelPlanData calculateRoutes(TravelPlanData planData, RawItinerary rawItinerary) {
        for (int dayIndex = 0; dayIndex < planData.getDays().size(); dayIndex++) {
            TravelDay day = planData.getDays().get(dayIndex);
            List<TravelPlace> places = day.getPlaces();
            if (places.size() < 2) continue;

            List<TravelRouteSegment> routes = new ArrayList<>();
            for (int i = 0; i < places.size() - 1; i++) {
                TravelPlace from = places.get(i);
                TravelPlace to = places.get(i + 1);
                String transportMode = "步行";

                List<RawDay> rawDays = rawItinerary.getDays();
                if (rawDays != null && rawDays.size() > dayIndex) {
                    RawDay rawDay = rawDays.get(dayIndex);
                    List<RawPlace> rawPlaces = rawDay.getPlaces();
                    if (rawPlaces != null && rawPlaces.size() > i) {
                        RawPlace rawPlace = rawPlaces.get(i);
                        if (rawPlace.transportMode != null) {
                            transportMode = rawPlace.transportMode;
                        }
                    }
                }

                List<Coordinate> polyline = generateStraightLine(from, to);

                routes.add(TravelRouteSegment.builder()
                        .fromIndex(i)
                        .toIndex(i + 1)
                        .transportMode(transportMode)
                        .duration("15分钟")
                        .distance("约1公里")
                        .polyline(polyline)
                        .build());
            }
            day.setRoutes(routes);
        }
        return planData;
    }

    private List<Coordinate> generateStraightLine(TravelPlace from, TravelPlace to) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            double ratio = i / 10.0;
            points.add(Coordinate.builder()
                    .longitude(from.getLongitude() + (to.getLongitude() - from.getLongitude()) * ratio)
                    .latitude(from.getLatitude() + (to.getLatitude() - from.getLatitude()) * ratio)
                    .build());
        }
        return points;
    }

    private MapBounds calculateViewport(TravelPlanData data) {
        double minLng = Double.MAX_VALUE, maxLng = Double.MIN_VALUE;
        double minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
        boolean hasValid = false;

        for (TravelDay day : data.getDays()) {
            for (TravelPlace place : day.getPlaces()) {
                if (place.getLongitude() != 0 || place.getLatitude() != 0) {
                    hasValid = true;
                    minLng = Math.min(minLng, place.getLongitude());
                    maxLng = Math.max(maxLng, place.getLongitude());
                    minLat = Math.min(minLat, place.getLatitude());
                    maxLat = Math.max(maxLat, place.getLatitude());
                }
            }
        }

        if (!hasValid) {
            return MapBounds.builder().minLng(116.0).maxLng(117.0).minLat(39.0).maxLat(40.0).build();
        }

        double padding = 0.05;
        return MapBounds.builder()
                .minLng(minLng - padding)
                .maxLng(maxLng + padding)
                .minLat(minLat - padding)
                .maxLat(maxLat + padding)
                .build();
    }

    private String getCityCenter(String destination) {
        switch (destination) {
            case "上海": return "121.4737,31.2304";
            case "北京": return "116.3974,39.9093";
            case "杭州": return "120.1551,30.2741";
            case "成都": return "104.0668,30.5728";
            case "广州": return "113.2644,23.1291";
            default: return "116.3974,39.9093";
        }
    }

    private List<String> generateDefaultTips(String destination) {
        return Arrays.asList(
                "📌 建议提前规划行程",
                "🚌 优先使用公共交通",
                "💡 查看" + destination + "当地天气",
                "🎫 热门景点建议提前购票"
        );
    }

    private String formatSearchResults(List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "（无搜索结果）";
        }
        return results.stream()
                .filter(r -> r != null)
                .map(r -> {
                    String title = r.title() != null ? r.title() : "无标题";
                    String content = r.snippet() != null ? r.snippet() : "";
                    if (content.isEmpty() && r.summary() != null) {
                        content = r.summary();
                    }
                    return "标题: " + title + "\n内容: " + content;
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    // ============================================================
    //  降级方案
    // ============================================================

    private TravelPlanData generateFallbackPlan(String destination, int days) {
        log.info("[TravelPlanner] 使用降级方案");
        MockDataService mockService = new MockDataService();
        return mockService.generateMockDataWithRoutes(destination, days);
    }

    private String generateDefaultItineraryJson(String destination, int days) {
        return String.format("""
                {
                  "city": "%s",
                  "totalDays": %d,
                  "days": [
                    {
                      "day": 1,
                      "theme": "经典游览",
                      "places": [
                        {"name": "%s景点A", "type": "景点", "description": "当地标志性景点", "duration": "2小时", "transportMode": "步行"},
                        {"name": "%s景点B", "type": "景点", "description": "值得打卡的地方", "duration": "2小时", "transportMode": "公交"}
                      ]
                    }
                  ],
                  "tips": ["建议提前规划", "注意天气变化"]
                }
                """, destination, days, destination, destination);
    }

    private RawItinerary generateDefaultRawItinerary(String destination, int days) {
        RawItinerary raw = new RawItinerary();
        raw.city = destination;
        raw.totalDays = days;
        raw.days = new ArrayList<>();
        raw.tips = Arrays.asList("建议提前规划", "注意天气变化");

        RawDay day = new RawDay();
        day.day = 1;
        day.theme = "经典游览";
        day.places = new ArrayList<>();

        RawPlace p1 = new RawPlace();
        p1.name = destination + "景点A";
        p1.type = "景点";
        p1.description = "当地标志性景点";
        p1.duration = "2小时";
        p1.transportMode = "步行";
        day.places.add(p1);

        raw.days.add(day);
        return raw;
    }

    // ============================================================
    //  内部类
    // ============================================================

    public static class RawItinerary {
        public String city;
        public String destination;
        public int totalDays;
        public String duration;
        public String preference;
        public List<RawDay> days;
        public List<RawDay> itinerary;
        public List<String> tips;
        public List<String> recommended_spots;
        public String accommodation;

        public List<String> getAllPlaceNames() {
            List<String> names = new ArrayList<>();
            if (days != null) {
                for (RawDay day : days) {
                    if (day.places != null) {
                        for (RawPlace place : day.places) {
                            if (place.name != null) names.add(place.name);
                        }
                    }
                }
            }
            if (itinerary != null) {
                for (RawDay day : itinerary) {
                    if (day.places != null) {
                        for (RawPlace place : day.places) {
                            if (place.name != null) names.add(place.name);
                        }
                    }
                }
            }
            if (recommended_spots != null) {
                names.addAll(recommended_spots);
            }
            return names;
        }

        public List<RawDay> getDays() {
            if (days != null && !days.isEmpty()) {
                return days;
            }
            if (itinerary != null && !itinerary.isEmpty()) {
                return itinerary;
            }
            return new ArrayList<>();
        }

        public List<String> getTips() {
            return tips != null ? tips : new ArrayList<>();
        }

        public String getCity() {
            return city != null ? city : destination;
        }

        public int getTotalDays() {
            if (totalDays > 0) return totalDays;
            if (duration != null) {
                try {
                    return Integer.parseInt(duration.replaceAll("[^0-9]", ""));
                } catch (Exception e) {}
            }
            return getDays().size();
        }
    }

    public static class RawDay {
        public int day;
        public String theme;
        public String 主题;
        public String 上午;
        public String 下午;
        public String 晚上;
        public String 说明;
        public List<RawPlace> places;

        public List<RawPlace> getPlaces() {
            if (places != null && !places.isEmpty()) {
                return places;
            }
            List<RawPlace> result = new ArrayList<>();
            if (上午 != null && !上午.isBlank()) {
                RawPlace p = new RawPlace();
                p.name = 上午;
                p.description = "上午游览";
                p.duration = "2小时";
                p.transportMode = "步行";
                result.add(p);
            }
            if (下午 != null && !下午.isBlank()) {
                RawPlace p = new RawPlace();
                p.name = 下午;
                p.description = "下午游览";
                p.duration = "2小时";
                p.transportMode = "步行";
                result.add(p);
            }
            if (晚上 != null && !晚上.isBlank()) {
                RawPlace p = new RawPlace();
                p.name = 晚上;
                p.description = "晚上游览";
                p.duration = "1.5小时";
                p.transportMode = "步行";
                result.add(p);
            }
            return result.isEmpty() ? new ArrayList<>() : result;
        }

        public int getDay() {
            return day;
        }

        public String getTheme() {
            return theme != null ? theme : 主题;
        }
    }

    public static class RawPlace {
        public String name;
        public String type;
        public String description;
        public String duration;
        public String transportMode;
    }
}