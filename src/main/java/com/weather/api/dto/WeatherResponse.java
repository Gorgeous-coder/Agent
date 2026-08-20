package com.weather.api.dto;

import lombok.Data;

@Data
public class WeatherResponse {
    private String city;
    private String temp;
    private String feelsLike;
    private String weatherDesc;
    private String humidity;
    private String windSpeed;
    private String windDir;
    private String pressure;
    private String error;  // 错误信息
}