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
public class TravelDay {
    private int day;
    private String weather;
    private List<TravelPlace> places;
    private List<TravelRouteSegment> routes;  // ⭐ 新增：景点间的路线
    private String summary;                   // ⭐ 新增：当日总结
}