package com.location.dto;

import java.util.List;

/**
 * 公交/地铁/高铁综合路径规划结果（高德 v5 公交路径规划接口）。
 *
 * <p>一个 {@code plans} 代表一个完整换乘方案（如：地铁→高铁→地铁），
 * 由多段 {@code segments} 组成。</p>
 */
public record TransitRouteResult(
        List<TransitPlan> plans
) {

    /**
     * 一个完整换乘方案。
     *
     * @param durationSeconds 总耗时（秒）
     * @param distanceMeters  总距离（米）
     * @param transitFeeYuan  公共交通总票价（元，0 表示未返回）
     * @param segments        分段行程
     */
    public record TransitPlan(
            int durationSeconds,
            int distanceMeters,
            int transitFeeYuan,
            List<Segment> segments
    ) {
    }

    /**
     * 一段行程：步行、地铁/公交或高铁。
     *
     * @param type   段类型：walking=步行，bus=地铁/公交，railway=高铁/火车
     * @param name   线路名称，如"地铁1号线"、"G42次高铁"
     * @param detail 该段的补充描述，如"杭州东站 08:30 → 北京南站 12:48"
     */
    public record Segment(
            String type,
            String name,
            String detail
    ) {
    }
}
