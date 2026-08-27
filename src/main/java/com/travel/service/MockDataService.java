package com.travel.service;

import com.travel.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class MockDataService {

    // ============================================================
    //  ⭐ 新增：生成带路线的 Mock 数据（供前端测试路线绘制）
    // ============================================================

    public TravelPlanData generateMockDataWithRoutes(String destination, int days) {
        log.info("[MockData] 生成带路线的模拟数据: destination={}, days={}", destination, days);

        // 1. 先生成基础数据
        TravelPlanData data = generateMockData(destination, days);

        // 2. 为每一天生成路线
        for (TravelDay day : data.getDays()) {
            List<TravelRouteSegment> routes = generateRoutesForDay(day.getPlaces());
            day.setRoutes(routes);
            day.setSummary("第" + day.getDay() + "天：探索" + destination + "的魅力");
        }

        // 3. 计算地图视野
        data.setViewport(calculateViewport(data));
        data.setCityCenter(getCityCenter(destination));
        data.setTips(generateDefaultTips(destination));

        return data;
    }

    /**
     * 生成上海完整数据（含路线）- 用于快速测试
     */
    public TravelPlanData generateMockShanghaiDataWithRoutes() {
        log.info("[MockData] 生成上海三日游模拟数据（含路线）");

        // 复用原有的上海数据
        TravelPlanData data = generateMockShanghaiData();

        // 为每一天生成路线
        for (TravelDay day : data.getDays()) {
            List<TravelRouteSegment> routes = generateRoutesForDay(day.getPlaces());
            day.setRoutes(routes);
            day.setSummary(getShanghaiDaySummary(day.getDay()));
        }

        data.setViewport(calculateViewport(data));
        data.setCityCenter("121.4737,31.2304");
        data.setTips(Arrays.asList(
                "🚇 上海地铁覆盖全城，下载Metro大都会APP扫码乘车",
                "🌧️ 夏季多阵雨，建议随身带伞",
                "📱 热门景点提前在官方小程序预约门票",
                "💰 外滩、南京路步行街免费，迪士尼门票提前7天购买有优惠"
        ));

        return data;
    }

    // ============================================================
    //  🔧 辅助方法：路线生成
    // ============================================================

    private List<TravelRouteSegment> generateRoutesForDay(List<TravelPlace> places) {
        if (places == null || places.size() < 2) {
            return Collections.emptyList();
        }

        List<TravelRouteSegment> routes = new ArrayList<>();
        String[] transportModes = {"步行", "公交", "打车"};
        String[] durations = {"15分钟", "20分钟", "10分钟"};
        String[] distances = {"约800米", "约1.5公里", "约2公里"};

        for (int i = 0; i < places.size() - 1; i++) {
            TravelPlace from = places.get(i);
            TravelPlace to = places.get(i + 1);

            int modeIndex = i % transportModes.length;

            routes.add(TravelRouteSegment.builder()
                    .fromIndex(i)
                    .toIndex(i + 1)
                    .transportMode(transportModes[modeIndex])
                    .duration(durations[modeIndex])
                    .distance(distances[modeIndex])
                    .polyline(generateMockPolyline(from, to))
                    .build());
        }

        return routes;
    }

    private List<Coordinate> generateMockPolyline(TravelPlace from, TravelPlace to) {
        List<Coordinate> points = new ArrayList<>();
        int steps = 20;

        for (int i = 0; i <= steps; i++) {
            double ratio = (double) i / steps;
            double lng = from.getLongitude() + (to.getLongitude() - from.getLongitude()) * ratio;
            double lat = from.getLatitude() + (to.getLatitude() - from.getLatitude()) * ratio;

            // 加一点小偏移，显得更自然（不走直线）
            if (i > 0 && i < steps) {
                double offset = 0.0003 * Math.sin(i * 1.5);
                lng += offset * (i % 2 == 0 ? 1 : -1);
                lat += offset * 0.5 * (i % 3 == 0 ? 1 : -1);
            }

            points.add(Coordinate.builder()
                    .longitude(Math.round(lng * 100000) / 100000.0)
                    .latitude(Math.round(lat * 100000) / 100000.0)
                    .build());
        }
        return points;
    }

    private MapBounds calculateViewport(TravelPlanData data) {
        double minLng = Double.MAX_VALUE;
        double maxLng = Double.MIN_VALUE;
        double minLat = Double.MAX_VALUE;
        double maxLat = Double.MIN_VALUE;
        boolean hasValidCoord = false;

        for (TravelDay day : data.getDays()) {
            for (TravelPlace place : day.getPlaces()) {
                if (place.getLongitude() != 0 || place.getLatitude() != 0) {
                    hasValidCoord = true;
                    minLng = Math.min(minLng, place.getLongitude());
                    maxLng = Math.max(maxLng, place.getLongitude());
                    minLat = Math.min(minLat, place.getLatitude());
                    maxLat = Math.max(maxLat, place.getLatitude());
                }
            }
        }

        if (!hasValidCoord) {
            return MapBounds.builder()
                    .minLng(116.0).maxLng(117.0)
                    .minLat(39.0).maxLat(40.0)
                    .build();
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
                "📌 建议提前规划行程，合理安排时间",
                "🚌 优先使用公共交通，避免堵车",
                "💡 查看" + destination + "当地天气，准备合适的衣物",
                "🎫 热门景点建议提前网上购票"
        );
    }

    private String getShanghaiDaySummary(int day) {
        String[] summaries = {
                "🌅 第一天：感受魔都的繁华与历史，漫步外滩欣赏万国建筑群",
                "🎢 第二天：沉浸在迪士尼的梦幻世界，体验最刺激的游乐项目",
                "🏯 第三天：品味老上海的文化底蕴，豫园赏江南园林，登上海中心俯瞰全城"
        };
        return summaries[(day - 1) % summaries.length];
    }

    // ============================================================
    //  📦 你原有的方法（保持不变）
    // ============================================================

    public TravelPlanData generateMockShanghaiData() {
        log.info("[MockData] 生成上海三日游模拟数据");

        TravelDay day1 = TravelDay.builder()
                .day(1)
                .weather("晴 28°C，适合户外活动")
                .places(List.of(
                        TravelPlace.builder()
                                .name("外滩")
                                .type("景点")
                                .longitude(121.4905)
                                .latitude(31.2410)
                                .duration("2小时")
                                .description("上海标志性景点，推荐傍晚前往，可观赏浦江两岸日落与夜景灯光。免费开放。")
                                .build(),
                        TravelPlace.builder()
                                .name("南京路步行街")
                                .type("购物")
                                .longitude(121.4780)
                                .latitude(31.2360)
                                .duration("1.5小时")
                                .description("中华商业第一街，购物逛街好去处，从外滩步行可达。")
                                .build(),
                        TravelPlace.builder()
                                .name("老正兴菜馆")
                                .type("餐厅")
                                .longitude(121.4760)
                                .latitude(31.2310)
                                .duration("1小时")
                                .description("百年老字号本帮菜，推荐油爆虾、草头圈子、红烧肉。人均120元。")
                                .build()
                ))
                .build();

        TravelDay day2 = TravelDay.builder()
                .day(2)
                .weather("多云 26°C，注意防晒")
                .places(List.of(
                        TravelPlace.builder()
                                .name("上海迪士尼乐园")
                                .type("景点")
                                .longitude(121.6730)
                                .latitude(31.1440)
                                .duration("全天")
                                .description("建议买早鸟票，工作日人少。必玩：创极速光轮、加勒比海盗、飞越地平线。")
                                .build(),
                        TravelPlace.builder()
                                .name("迪士尼小镇")
                                .type("购物")
                                .longitude(121.6610)
                                .latitude(31.1410)
                                .duration("1.5小时")
                                .description("乐园出口处，餐饮购物一体，无需门票。推荐茉莉餐厅。")
                                .build(),
                        TravelPlace.builder()
                                .name("茉莉餐厅")
                                .type("餐厅")
                                .longitude(121.6620)
                                .latitude(31.1420)
                                .duration("1小时")
                                .description("迪士尼小镇内，中西融合菜，人均150元。")
                                .build()
                ))
                .build();

        TravelDay day3 = TravelDay.builder()
                .day(3)
                .weather("小雨 24°C，建议带伞")
                .places(List.of(
                        TravelPlace.builder()
                                .name("豫园")
                                .type("景点")
                                .longitude(121.4920)
                                .latitude(31.2270)
                                .duration("2小时")
                                .description("江南古典园林，门票40元。推荐上午前往，人少景美。")
                                .build(),
                        TravelPlace.builder()
                                .name("城隍庙")
                                .type("景点")
                                .longitude(121.4930)
                                .latitude(31.2260)
                                .duration("1小时")
                                .description("豫园旁，小吃天堂。推荐南翔小笼包、蟹黄汤包。")
                                .build(),
                        TravelPlace.builder()
                                .name("上海中心大厦")
                                .type("景点")
                                .longitude(121.5010)
                                .latitude(31.2350)
                                .duration("1.5小时")
                                .description("中国第一高楼，118层观光层门票180元。天气好可远眺整个上海。")
                                .build()
                ))
                .build();

        return TravelPlanData.builder()
                .destination("上海")
                .days(List.of(day1, day2, day3))
                .build();
    }

    public TravelPlanData generateMockData(String destination, int days) {
        log.info("[MockData] 生成通用模拟数据: destination={}, days={}", destination, days);

        double centerLng = 116.3974;
        double centerLat = 39.9093;
        String placePrefix = destination + "景点";
        String foodPrefix = destination + "美食";

        if ("上海".equals(destination)) {
            centerLng = 121.4737;
            centerLat = 31.2304;
        } else if ("北京".equals(destination)) {
            centerLng = 116.3974;
            centerLat = 39.9093;
        } else if ("杭州".equals(destination)) {
            centerLng = 120.1551;
            centerLat = 30.2741;
        } else if ("成都".equals(destination)) {
            centerLng = 104.0668;
            centerLat = 30.5728;
        } else if ("广州".equals(destination)) {
            centerLng = 113.2644;
            centerLat = 23.1291;
        }

        String[] weathers = {"晴 28°C", "多云 26°C", "阴 25°C", "小雨 24°C 带伞", "晴转多云 27°C"};
        String[] placeTypes = {"景点", "景点", "餐厅", "购物", "景点", "餐厅"};
        String[] placeNames = {
                placePrefix + "A", placePrefix + "B", foodPrefix + "1号店",
                destination + "步行街", placePrefix + "C", foodPrefix + "2号店"
        };
        String[] durations = {"2小时", "1.5小时", "1小时", "1.5小时", "2小时", "1小时"};
        String[] descriptions = {
                "当地著名景点，推荐上午前往。",
                "必打卡地标，拍照好去处。",
                "当地特色美食，人均80元。",
                "逛街购物一体，适合休闲。",
                "历史文化景点，门票50元。",
                "老字号餐厅，人均120元。"
        };

        TravelDay[] dayArray = new TravelDay[days];
        for (int i = 0; i < days; i++) {
            int placesPerDay = 3;
            TravelPlace[] places = new TravelPlace[placesPerDay];
            for (int j = 0; j < placesPerDay; j++) {
                int idx = (i * placesPerDay + j) % placeNames.length;
                double lngOffset = (Math.random() - 0.5) * 0.08;
                double latOffset = (Math.random() - 0.5) * 0.06;
                places[j] = TravelPlace.builder()
                        .name(placeNames[idx])
                        .type(placeTypes[idx])
                        .longitude(Math.round((centerLng + lngOffset) * 10000) / 10000.0)
                        .latitude(Math.round((centerLat + latOffset) * 10000) / 10000.0)
                        .duration(durations[idx])
                        .description(descriptions[idx])
                        .build();
            }
            dayArray[i] = TravelDay.builder()
                    .day(i + 1)
                    .weather(weathers[i % weathers.length])
                    .places(List.of(places))
                    .build();
        }

        return TravelPlanData.builder()
                .destination(destination)
                .days(List.of(dayArray))
                .build();
    }
}