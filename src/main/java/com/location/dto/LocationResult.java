package com.location.dto;

/**
 * 地理编码
 */
public record LocationResult(
        String formattedAddress,
        String province,
        String city,
        String district,
        double longitude,
        double latitude
) {
}
