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
     * 从用户当前位置出发，规划到目的地的公交/地铁/高铁综合出行路线。
     *
     * @param destination 目的地地址，例如"北京站"
     * @param strategy    换乘策略：0=推荐，1=最经济，2=少换乘，3=少步行，5=不乘地铁，7=地铁优先，8=时间短
     */
    TransitRouteResult planTransitRoute(
            String userId,
            String destination,
            int strategy
    );
}
