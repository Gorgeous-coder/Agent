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
public class TravelRouteSegment {
    private int fromIndex;              // 起点在places列表中的索引
    private int toIndex;                // 终点索引
    private String transportMode;       // 步行/公交/地铁/打车/驾车
    private String duration;            // "15分钟"
    private String distance;            // "1.2公里"
    private List<Coordinate> polyline;  // 路径点列表（用于绘制路线）
}