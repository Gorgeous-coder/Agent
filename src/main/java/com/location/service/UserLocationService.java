package com.location.service;

import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.dto.TransitRouteResult;
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

    /**
     * 规划公共交通路线（公交/地铁/高铁）
     */
    TransitRouteResult planTransitRoute(String userId, String destination, int strategy);
}