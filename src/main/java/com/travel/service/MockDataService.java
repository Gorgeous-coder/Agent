package com.travel.service;

import com.travel.dto.TravelDay;
import com.travel.dto.TravelPlace;
import com.travel.dto.TravelPlanData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class MockDataService {

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

        TravelPlanData data = TravelPlanData.builder()
                .destination("上海")
                .days(List.of(day1, day2, day3))
                .build();

        log.info("[MockData] 模拟数据生成完成: destination={}, days={}", data.getDestination(), data.getDays().size());
        return data;
    }

    /**
     * 通用模拟数据生成：根据目的地和天数动态生成
     * 演示用：证明模板完全通用，不绑定上海3天
     */
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

        TravelPlanData data = TravelPlanData.builder()
                .destination(destination)
                .days(List.of(dayArray))
                .build();

        log.info("[MockData] 通用模拟数据生成完成: destination={}, days={}", data.getDestination(), data.getDays().size());
        return data;
    }
}
