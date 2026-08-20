package com.weather.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.weather.api.dto.WeatherResponse;
import com.weather.service.WeatherService;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * 天气查询接口
     *
     * @param city 城市名称（如：北京、上海）
     * @param type 查询类型：base（实时天气）或 all（未来几天预报）
     * @return WeatherResponse
     */
    @GetMapping("/weather/search")
    public WeatherResponse searchWeather(
            @RequestParam String city,
            @RequestParam(defaultValue = "base") String type) {

        log.info("🌤️ 收到天气查询请求: city={}, type={}", city, type);

        try {
            // 调用你的 WeatherService 实现
            WeatherResponse response = weatherService.getWeather(city);

            // 如果返回的 city 为空，说明查询失败
            if (response == null || response.getCity() == null) {
                WeatherResponse errorResponse = new WeatherResponse();
                errorResponse.setError("天气查询失败，请稍后重试");
                return errorResponse;
            }

            return response;

        } catch (Exception e) {
            log.error("❌ 天气查询异常: {}", e.getMessage(), e);
            WeatherResponse errorResponse = new WeatherResponse();
            errorResponse.setError("天气查询异常: " + e.getMessage());
            return errorResponse;
        }
    }
}