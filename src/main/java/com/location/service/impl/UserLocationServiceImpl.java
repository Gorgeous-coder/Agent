package com.location.service.impl;

import com.location.dto.TransitRouteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.location.dto.GeocodeResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.model.UserLocation;
import com.location.repository.UserLocationRepository;
import com.location.service.AmapLocationService;
import com.location.service.UserLocationService;
import com.weather.service.WeatherService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserLocationServiceImpl implements UserLocationService {

    private final UserLocationRepository userLocationRepository;
    private final AmapLocationService amapLocationService;
    private final WeatherService weatherService;

    @Override
    public UserLocation setCurrentLocation(String userId, String address) {
        requireUserId(userId);
        GeocodeResult geocode = amapLocationService.geocode(address);
        UserLocation location = new UserLocation(
                userId,
                geocode.formattedAddress(),
                geocode.city(),
                geocode.longitude(),
                geocode.latitude(),
                LocalDateTime.now()
        );
        userLocationRepository.save(location);
        return location;
    }

    @Override
    public UserLocation getCurrentLocation(String userId) {
        requireUserId(userId);
        return userLocationRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("请先告诉我您的位置，例如：我现在在杭州西湖区"));
    }

    @Override
    public String getLocalWeather(String userId, String type) {
        UserLocation location = getCurrentLocation(userId);
        String normalizedType = "all".equalsIgnoreCase(type)
                ? "all"
                : "base";
        return weatherService.getWeatherText(
                location.city(),
                normalizedType
        );
    }

    @Override
    public List<PlaceResult> searchNearby(
            String userId,
            String keyword,
            int radiusMeters
    ) {
        UserLocation location = getCurrentLocation(userId);
        return amapLocationService.searchNearby(
                location.longitude(),
                location.latitude(),
                keyword,
                radiusMeters
        );
    }

    @Override
    public RouteResult planRoute(
            String userId,
            String destination,
            String mode
    ) {
        UserLocation origin = getCurrentLocation(userId);
        GeocodeResult destinationPoint = amapLocationService.geocode(destination);
        return amapLocationService.planRoute(
                origin.longitude(),
                origin.latitude(),
                destinationPoint.longitude(),
                destinationPoint.latitude(),
                mode
        );
    }

    @Override
    public TransitRouteResult planTransitRoute(String userId, String destination, int strategy) {
        return null;
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new RuntimeException("无法识别当前微信用户");
        }
    }
}