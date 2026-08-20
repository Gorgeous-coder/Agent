package com.weather.service.impl;

import com.weather.api.dto.WeatherResponse;
import com.weather.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public WeatherResponse getWeather(String city) {
        try {
            log.info("🔍 查询天气，城市: {}", city);

            // 1. 调用 wttr.in 免费天气 API
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.toString());
            String url = String.format("https://wttr.in/%s?format=j1&lang=zh", encodedCity);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("❌ wttr.in 请求失败: {}", response.statusCode());
                return buildErrorResponse("天气服务暂时不可用");
            }

            // 2. 解析 JSON
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.has("current_condition") || root.path("current_condition").isEmpty()) {
                return buildErrorResponse("未找到该城市天气数据");
            }

            JsonNode current = root.path("current_condition").path(0);
            String temp = current.path("temp_C").asText();
            String feelsLike = current.path("FeelsLikeC").asText();
            String weatherDesc = current.path("weatherDesc").path(0).path("value").asText();
            String humidity = current.path("humidity").asText();
            String windSpeed = current.path("windspeedKmph").asText();
            String windDir = current.path("winddir16Point").asText();
            String pressure = current.path("pressure").asText();

            // 3. 组装返回对象
            WeatherResponse result = new WeatherResponse();
            result.setCity(city);
            result.setTemp(temp);
            result.setFeelsLike(feelsLike);
            result.setWeatherDesc(weatherDesc);
            result.setHumidity(humidity);
            result.setWindSpeed(windSpeed);
            result.setWindDir(windDir);
            result.setPressure(pressure);

            log.info("✅ 天气查询成功: {} - {}℃", city, temp);
            return result;

        } catch (Exception e) {
            log.error("❌ 天气查询异常: {}", e.getMessage(), e);
            return buildErrorResponse("天气查询异常: " + e.getMessage());
        }
    }

    @Override
    public String getWeatherText(String city, String type) {
        WeatherResponse result = getWeather(city);
        if (result == null || result.getTemp() == null) {
            return "⚠️ 天气查询失败，请稍后重试";
        }

        // 如果是 all 类型，返回更详细的预报（这里简化，只返回当前天气）
        return String.format(
                "🌤️ %s天气\n" +
                        "🌡️ 温度：%s℃（体感 %s℃）\n" +
                        "☁️ 天气状况：%s\n" +
                        "💨 风向：%s，风速：%s km/h\n" +
                        "💧 湿度：%s%%\n" +
                        "🌊 气压：%s mb",
                result.getCity(),
                result.getTemp(),
                result.getFeelsLike(),
                result.getWeatherDesc(),
                result.getWindDir(),
                result.getWindSpeed(),
                result.getHumidity(),
                result.getPressure()
        );
    }

    private WeatherResponse buildErrorResponse(String message) {
        WeatherResponse result = new WeatherResponse();
        result.setError(message);
        return result;
    }
}