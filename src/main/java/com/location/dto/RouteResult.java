package com.location.dto;

import java.util.List;

/**
 * 路线规划
 */
public record RouteResult(
        String mode,        //方式
        int distanceMeters, //距离
        int durationSeconds,
        List<String> steps   //步骤
) {
}
