package com.location.service;

import com.location.dto.GeocodeResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.dto.TransitRouteResult;

import java.util.List;

public interface AmapLocationService {

    GeocodeResult geocode(String address);

    List<PlaceResult> searchNearby(
            double longitude,
            double latitude,
            String keyword,
            int radiusMeters
    );

    RouteResult planRoute(
            double originLongitude,
            double originLatitude,
            double destinationLongitude,
            double destinationLatitude,
            String mode
    );

    /**
     * 规划公共交通路线（公交/地铁/高铁）
     */
    TransitRouteResult planTransitRoute(
            double originLongitude,
            double originLatitude,
            double destinationLongitude,
            double destinationLatitude,
            String citycode1,
            String citycode2,
            int strategy
    );
}