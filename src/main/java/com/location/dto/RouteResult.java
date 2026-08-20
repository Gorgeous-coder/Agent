package com.location.dto;

import java.util.List;

/**
 * 路线规划。
 */
public record RouteResult(
        String mode,
        int distanceMeters,
        int durationSeconds,
        List<String> steps
) {
}
