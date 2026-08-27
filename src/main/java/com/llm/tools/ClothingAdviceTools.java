package com.llm.tools;

import com.location.model.UserLocation;
import com.location.service.UserLocationService;
import com.processor.UserContext;
import com.weather.api.dto.WeatherResponse;
import com.weather.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClothingAdviceTools {

    private final WeatherService weatherService;
    private final UserLocationService userLocationService;
    private final UserContext userContext;

    public ClothingAdviceTools(WeatherService weatherService,
                               UserLocationService userLocationService,
                               UserContext userContext) {
        this.weatherService = weatherService;
        this.userLocationService = userLocationService;
        this.userContext = userContext;
    }

    @Tool(description = "根据实时天气给出穿衣搭配建议。当用户问穿什么、怎么穿、带不带伞、穿衣推荐时调用此工具。")
    public String suggestOutfit(
            @ToolParam(description = "城市名，如北京、上海。用户没说城市时传空字符串", required = false)
            String city,
            @ToolParam(description = "出行场景，如通勤、约会、运动、旅行。用户未说明时传空", required = false)
            String occasion
    ) {
        try {
            String resolvedCity = resolveCity(city);
            WeatherResponse weather = weatherService.getWeather(resolvedCity);
            if (weather == null || weather.getCity() == null || weather.getError() != null) {
                String error = weather != null && weather.getError() != null
                        ? weather.getError()
                        : "天气查询失败，请稍后重试";
                return "❌ 无法给出穿衣建议：" + error;
            }

            double feelsLike = parseTemp(weather.getFeelsLike(), parseTemp(weather.getTemp(), 20));
            String desc = nullToEmpty(weather.getWeatherDesc());
            double wind = parseNumber(weather.getWindSpeed());
            double humidity = parseNumber(weather.getHumidity());
            String scene = (occasion == null || occasion.isBlank()) ? "日常出行" : occasion.trim();

            log.info("[ClothingAdvice] city={}, feelsLike={}, desc={}, occasion={}",
                    resolvedCity, feelsLike, desc, scene);

            return buildAdvice(weather, feelsLike, desc, wind, humidity, scene);
        } catch (Exception e) {
            log.warn("[ClothingAdvice] 生成失败: city={}, error={}", city, e.getMessage());
            return "❌ 穿衣建议失败：" + e.getMessage();
        }
    }

    private String resolveCity(String city) {
        if (city != null && !city.isBlank()) {
            return city.trim();
        }
        String userId = userContext.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new RuntimeException("请先告诉我城市，例如：北京今天穿什么");
        }
        UserLocation location = userLocationService.getCurrentLocation(userId);
        return location.city();
    }

    private String buildAdvice(WeatherResponse weather, double feelsLike, String desc,
                               double wind, double humidity, String occasion) {
        String layer = layerAdvice(feelsLike);
        String extras = extraAdvice(desc, wind, humidity);
        String sceneTip = sceneAdvice(occasion, feelsLike, desc);

        return """
                👗 %s穿衣建议（体感 %s℃，%s）
                🧥 主搭配：%s
                ☔ 补充：%s
                🎯 场景（%s）：%s
                """.formatted(
                weather.getCity(),
                weather.getFeelsLike() != null ? weather.getFeelsLike() : weather.getTemp(),
                weather.getWeatherDesc(),
                layer,
                extras,
                occasion,
                sceneTip
        ).trim();
    }

    private String layerAdvice(double feelsLike) {
        if (feelsLike <= 0) {
            return "羽绒服或厚呢大衣 + 毛衣/卫衣 + 保暖内衣；下装选加绒裤，帽子手套围巾齐全，穿防滑靴";
        }
        if (feelsLike <= 8) {
            return "厚外套（大衣/棉服）+ 针织衫或薄毛衣；长裤，可加围巾，鞋子选保暖款";
        }
        if (feelsLike <= 15) {
            return "风衣、夹克或薄大衣 + 长袖衬衫/卫衣；长裤，早晚可加一件薄针织";
        }
        if (feelsLike <= 22) {
            return "衬衫、薄卫衣或针织开衫；长裤或薄休闲裤，方便单穿或外搭一件";
        }
        if (feelsLike <= 28) {
            return "短袖 T 恤或薄衬衫；长裤、短裤或裙装均可，备一件极薄外套防室内空调";
        }
        return "短袖、吊带或透气棉麻上衣 + 短裤/短裙；选浅色透气面料，注意防晒";
    }

    private String extraAdvice(String desc, double wind, double humidity) {
        StringBuilder extra = new StringBuilder();
        String lower = desc.toLowerCase();
        boolean rain = containsAny(desc, "雨", "雷", "shower", "rain");
        boolean snow = containsAny(desc, "雪", "snow");
        boolean sun = containsAny(desc, "晴", "烈日", "sunny", "clear");

        if (snow) {
            extra.append("有雪，穿防水防滑靴，注意保暖防滑。");
        } else if (rain) {
            extra.append("有雨，带折叠伞，外套选防水或快干面料，避免浅色帆布鞋。");
        } else {
            extra.append("暂无明显降雨，伞可按需携带。");
        }

        if (sun && !rain) {
            extra.append(" 阳光较强时可戴帽、墨镜，涂防晒。");
        }
        if (wind >= 25) {
            extra.append(" 风力较大，外套选防风款，帽子围巾要固定。");
        }
        if (humidity >= 80 && !rain) {
            extra.append(" 湿度偏高，选透气面料，少穿不透气的厚层。");
        }
        if (lower.contains("雾") || lower.contains("霾")) {
            extra.append(" 能见度偏差，外出可备口罩。");
        }
        return extra.toString().trim();
    }

    private String sceneAdvice(String occasion, double feelsLike, String desc) {
        String scene = occasion.toLowerCase();
        boolean rain = containsAny(desc, "雨", "雷", "shower", "rain");
        if (scene.contains("运动") || scene.contains("跑步") || scene.contains("健身")) {
            return rain
                    ? "改室内运动，或穿速干套装并带防水外套"
                    : (feelsLike <= 10 ? "加绒运动长裤 + 速干长袖，注意热身" : "速干短袖/长袖 + 运动裤，便于排汗");
        }
        if (scene.contains("约会") || scene.contains("通勤") || scene.contains("上班") || scene.contains("正装")) {
            return feelsLike <= 15
                    ? "内搭衬衫，外穿大衣或西装外套，配深色长裤/裙，注意鞋面防滑"
                    : "衬衫或针织衫即可，外套可搭在包里，鞋履选舒适正装款";
        }
        if (scene.contains("旅行") || scene.contains("出游") || scene.contains("户外")) {
            return "分层穿，方便加减衣；背包放一件薄外套" + (rain ? "和折叠伞" : "");
        }
        return "按主搭配增减一层即可，以体感温度为准。";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double parseTemp(String value, double fallback) {
        Double parsed = tryParse(value);
        return parsed != null ? parsed : fallback;
    }

    private double parseNumber(String value) {
        Double parsed = tryParse(value);
        return parsed != null ? parsed : 0;
    }

    private Double tryParse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
