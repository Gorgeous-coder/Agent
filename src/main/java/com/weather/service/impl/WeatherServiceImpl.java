package com.weather.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.weather.api.dto.ForecastDay;
import com.weather.api.dto.WeatherResponse;
import com.weather.service.WeatherService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@SuppressWarnings("unused")
public class WeatherServiceImpl implements WeatherService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${gaode.key}")
    private String apiKey;

    private static final String GAODE_URL =
            "https://restapi.amap.com/v3/weather/weatherInfo?key={key}&city={city}&extensions={type}";
    private static final String DISTRICT_URL =
            "https://restapi.amap.com/v3/config/district?key={key}&keywords={keywords}&subdistrict=0";

    @Override
    public WeatherResponse getWeatherByCity(String city, String type) {
        if (city == null || city.isBlank()) {
            throw new RuntimeException("城市名不能为空");
        }
        city = city.trim();

        String adcode = city.matches("\\d+") ? city : resolveCityCode(city).get("adcode");
        if (adcode == null) {
            throw new RuntimeException("未找到城市编码: " + city);
        }

        try {
            String json = restTemplate.getForObject(GAODE_URL, String.class, apiKey, adcode, type);
            JsonNode jsonNode = objectMapper.readTree(json);
            if (!"1".equals(jsonNode.path("status").asString())) {
                throw new RuntimeException("天气查询失败");
            }
            return "base".equals(type) ? parseLive(jsonNode) : parseForecast(jsonNode);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("网络错误: " + e.getMessage());
        }
    }

    @Override
    public String getWeatherText(String city, String type) {
        WeatherResponse w = getWeatherByCity(city, type);

        if ("all".equals(type)) {
            if (w.forecast() == null || w.forecast().days() == null || w.forecast().days().isEmpty()) {
                return "暂无预报数据";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(w.city()).append("未来天气：\n");
            for (ForecastDay day : w.forecast().days()) {
                sb.append(String.format("%s(%s) 白天%s %s°C 夜间%s %s°C\n",
                        day.date(), day.week(),
                        day.day().weather(), day.day().temp(),
                        day.night().weather(), day.night().temp()));
            }
            return sb.toString().trim();
        }

        WeatherResponse.LiveWeather live = w.live();
        if (live == null) {
            return "暂无实时天气数据";
        }
        return String.format("%s %s°C %s 湿度%s%% 风力%s级",
                live.weather(), live.temperature(), live.windDirection(), live.humidity(), live.windPower());
    }

    private WeatherResponse parseLive(JsonNode root) {
        JsonNode liveNode = root.path("lives").get(0);

        WeatherResponse.LiveWeather liveWeather = WeatherResponse.LiveWeather.builder()
                .weather(liveNode.path("weather").asString())
                .temperature(liveNode.path("temperature").asString())
                .humidity(liveNode.path("humidity").asString())
                .windDirection(liveNode.path("winddirection").asString())
                .windPower(liveNode.path("windpower").asString())
                .build();

        return WeatherResponse.builder()
                .province(liveNode.path("province").asString())
                .city(liveNode.path("city").asString())
                .reportTime(liveNode.path("reporttime").asString())
                .live(liveWeather)
                .build();
    }

    private WeatherResponse parseForecast(JsonNode root) {
        JsonNode forecastNode = root.path("forecasts").get(0);

        List<ForecastDay> list = new ArrayList<>();
        for (JsonNode cast : forecastNode.path("casts")) {
            ForecastDay.PeriodDetail dayDetail = ForecastDay.PeriodDetail.builder()
                    .weather(cast.path("dayweather").asString())
                    .temp(cast.path("daytemp").asString())
                    .wind(cast.path("daywind").asString())
                    .power(cast.path("daypower").asString())
                    .build();

            ForecastDay.PeriodDetail nightDetail = ForecastDay.PeriodDetail.builder()
                    .weather(cast.path("nightweather").asString())
                    .temp(cast.path("nighttemp").asString())
                    .wind(cast.path("nightwind").asString())
                    .power(cast.path("nightpower").asString())
                    .build();

            list.add(ForecastDay.builder()
                    .date(cast.path("date").asString())
                    .week(cast.path("week").asString())
                    .day(dayDetail)
                    .night(nightDetail)
                    .build());
        }

        WeatherResponse.ForecastInfo forecastInfo = WeatherResponse.ForecastInfo.builder()
                .type("all")
                .days(list)
                .build();

        return WeatherResponse.builder()
                .province(forecastNode.path("province").asString())
                .city(forecastNode.path("city").asString())
                .reportTime(forecastNode.path("reporttime").asString())
                .forecast(forecastInfo)
                .build();
    }

    private Map<String, String> resolveCityCode(String keyword) {
        try {
            String json = restTemplate.getForObject(DISTRICT_URL, String.class, apiKey, keyword);
            JsonNode root = objectMapper.readTree(json);
            JsonNode district = root.path("districts").get(0);
            if (district == null || district.path("adcode").asString().isEmpty()) {
                throw new RuntimeException("未找到城市: " + keyword);
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("city", district.path("name").asString());
            result.put("adcode", district.path("adcode").asString());
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("网络错误: " + e.getMessage());
        }
    }
}