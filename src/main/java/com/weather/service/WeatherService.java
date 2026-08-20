package com.weather.service;

import com.weather.api.dto.WeatherResponse;

public interface WeatherService {
    WeatherResponse getWeather(String city);
    String getWeatherText(String city, String type);
}