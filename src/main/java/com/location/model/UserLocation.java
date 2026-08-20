package com.location.model;

import java.time.LocalDateTime;

/**
 * 微信用户最近一次主动设置的位置
 */
public record UserLocation(
        String userId,
        String address,
        String city,
        double longitude,
        double latitude,
        LocalDateTime updatedAt
) {
}
