package com.travel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapBounds {
    private double minLng;
    private double maxLng;
    private double minLat;
    private double maxLat;

    // 计算中心点
    public double getCenterLng() {
        return (minLng + maxLng) / 2;
    }

    public double getCenterLat() {
        return (minLat + maxLat) / 2;
    }
}