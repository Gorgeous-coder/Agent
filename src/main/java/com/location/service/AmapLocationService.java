package com.location.service;

import com.location.dto.GeocodeResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.dto.TransitRouteResult;

import java.util.List;

public interface AmapLocationService {
    /**
     * 将文字地址转换成经纬度和城市信息。
     *
     * @param address 地址，例如“杭州西湖区”
     * @return 高德解析结果
     */
    GeocodeResult geocode(String address);

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

    /**
     * 规划公交/地铁/高铁综合出行路线（高德 v5 公交路径规划接口）。
     *
     * @param citycode1 起点城市 citycode，例如淮安 0517
     * @param citycode2 终点城市 citycode，例如南京 025
     * @param strategy 换乘策略：0=推荐，1=最经济，2=少换乘，3=少步行，5=不乘地铁，7=地铁优先，8=时间短
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
