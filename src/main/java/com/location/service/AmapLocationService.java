package com.location.service;

import com.location.dto.LocationResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;

import java.util.List;

public interface AmapLocationService {
    /**
     * 将文字地址转换成经纬度和城市信息。
     *
     * @param address 地址，例如“北京海淀区”
     * @return 高德解析结果
     */
    LocationResult geocode(String address);

    /**
     * 按经纬度搜索附近地点。
     */
    List<PlaceResult> searchNearby(
            double longitude,
            double latitude,
            String keyword,
            int radiusMeters
    );

    /**
     * 规划步行或驾车路线。
     */
    RouteResult planRoute(
            double originLongitude,
            double originLatitude,
            double destinationLongitude,
            double destinationLatitude,
            String mode
    );
}
