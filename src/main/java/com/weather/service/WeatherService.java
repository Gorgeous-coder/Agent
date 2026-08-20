package com.weather.service;
import com.weather.api.dto.WeatherResponse;
public interface WeatherService {
    WeatherResponse getWeatherByCity(String city, String type);

    // WeatherService.java
    String getWeatherText(String city,String type);

}
