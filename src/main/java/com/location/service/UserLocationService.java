package com.location.service;

import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.model.UserLocation;

import java.util.List;

/**
 * 面向微信用户的位置业务服务。
 */
public interface UserLocationService {

    UserLocation setCurrentLocation(String userId, String address);

    UserLocation getCurrentLocation(String userId);

    String getLocalWeather(String userId, String type);

    List<PlaceResult> searchNearby(
            String userId,
            String keyword,
            int radiusMeters
    );

    RouteResult planRoute(
            String userId,
            String destination,
            String mode
    );
}
