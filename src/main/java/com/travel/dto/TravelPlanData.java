package com.travel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPlanData {
    private String destination;
    private List<TravelDay> days;
    private MapBounds viewport;        // ⭐ 新增：地图视野范围
    private String cityCenter;         // ⭐ 新增：城市中心坐标 "lng,lat"
    private List<String> tips;         // ⭐ 新增：旅行小贴士
}