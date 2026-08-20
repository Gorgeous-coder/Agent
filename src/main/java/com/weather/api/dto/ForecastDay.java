package com.weather.api.dto;

import lombok.Builder;

@Builder
public record ForecastDay(
        String date,
        String week,
        PeriodDetail day,     // 白天详情
        PeriodDetail night    // 夜间详情
) {
    // 内部记录类：封装白天或夜间的具体气象数据
    @Builder
    public record PeriodDetail(
            String weather,
            String temp,
            String wind,
            String power
    ) {}
}