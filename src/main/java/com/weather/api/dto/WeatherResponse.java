package com.weather.api.dto;

import lombok.Builder;
import java.util.List;

@Builder
public record WeatherResponse(
        String province,
        String city,
        String reportTime,
        LiveWeather live,            // 实况天气详情
        ForecastInfo forecast        // 预报天气详情
) {
    // 1. 实况天气子对象
    @Builder
    public record LiveWeather(
            String weather,
            String temperature,
            String humidity,
            String windDirection,
            String windPower
    ) {}

    // 2. 预报天气子对象
    @Builder
    public record ForecastInfo(
            String type,
            List<ForecastDay> days
    ) {}
}