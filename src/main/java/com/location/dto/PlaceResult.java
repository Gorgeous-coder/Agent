package com.location.dto;

/**
 * 地点搜索
 */
public record PlaceResult(
        String name,
        String address,
        String type,
        int distanceMeters,
        double longitude,
        double latitude
) {
}
