package com.group26.Agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class WeatherService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getWeather(String city) {
        try {
            System.out.println("🔍 查询天气，城市: " + city);
            String result = getWeatherFromOpenMeteo(city);
            if (result != null) {
                return result;
            }
            return "⚠️ 天气服务暂时不可用，请稍后重试。";
        } catch (Exception e) {
            System.err.println("❌ 天气查询异常: " + e.getMessage());
            return "⚠️ 天气查询异常: " + e.getMessage();
        }
    }

    /**
     * 使用 Open-Meteo 免费天气 API（无需注册、无需 Key）
     */
    private String getWeatherFromOpenMeteo(String city) {
        try {
            // 1. 先用城市名获取经纬度
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.toString());
            String geoUrl = String.format(
                    "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=zh",
                    encodedCity
            );

            System.out.println("🌐 [地理编码] " + geoUrl);

            HttpRequest geoRequest = HttpRequest.newBuilder()
                    .uri(URI.create(geoUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> geoResponse = httpClient.send(geoRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (geoResponse.statusCode() != 200) {
                return null;
            }

            JsonNode geoRoot = objectMapper.readTree(geoResponse.body());
            JsonNode results = geoRoot.path("results");
            if (results == null || results.isEmpty()) {
                return null;
            }

            String lat = results.path(0).path("latitude").asText();
            String lon = results.path(0).path("longitude").asText();
            String cityName = results.path(0).path("name").asText();
            String country = results.path(0).path("country").asText();

            System.out.println("📍 坐标: " + lat + ", " + lon);

            // 2. 获取实时天气
            String weatherUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current_weather=true&timezone=auto",
                    lat, lon
            );

            System.out.println("🌐 [Open-Meteo] " + weatherUrl);

            HttpRequest weatherRequest = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> weatherResponse = httpClient.send(weatherRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (weatherResponse.statusCode() != 200) {
                System.err.println("❌ Open-Meteo 请求失败: " + weatherResponse.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(weatherResponse.body());
            JsonNode current = root.path("current_weather");

            String temp = current.path("temperature").asText();
            String windSpeed = current.path("windspeed").asText();
            String windDir = current.path("winddirection").asText();
            String weatherCode = current.path("weathercode").asText();

            // 天气代码转文字
            String weatherDesc = weatherCodeToDesc(weatherCode);

            return String.format(
                    "🌤️ %s（%s）天气\n" +
                            "🌡️ 温度：%s℃\n" +
                            "☁️ 天气状况：%s\n" +
                            "💨 风速：%s km/h\n" +
                            "🧭 风向：%s°",
                    cityName, country, temp, weatherDesc, windSpeed, windDir
            );

        } catch (Exception e) {
            System.err.println("❌ Open-Meteo 查询异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Open-Meteo 天气代码转中文描述
     */
    private String weatherCodeToDesc(String code) {
        switch (code) {
            case "0": return "晴天";
            case "1": return "主要晴天";
            case "2": return "部分多云";
            case "3": return "多云";
            case "45": case "48": return "雾";
            case "51": case "53": case "55": return "毛毛雨";
            case "61": case "63": case "65": return "小雨/中雨/大雨";
            case "71": case "73": case "75": return "小雪/中雪/大雪";
            case "80": case "81": case "82": return "阵雨";
            case "95": case "96": case "99": return "雷暴";
            default: return "未知天气 (" + code + ")";
        }
    }
}