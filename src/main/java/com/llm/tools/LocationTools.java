package com.llm.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.dto.TransitRouteResult;
import com.location.model.UserLocation;
import com.location.service.UserLocationService;
import com.processor.UserContext;

import java.util.List;

/**
 * 面向大模型的定位、附近搜索和路线规划工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationTools {

    private final UserLocationService userLocationService;
    private final UserContext userContext;

    @Tool(description = "保存用户当前位置。仅当用户明确说‘我在某地’、‘把位置设置为某地’或‘记住我的位置’时调用")
    public String setCurrentLocation(
            @ToolParam(description = "用户所在的城市、区域或详细地址，例如：北京海淀区")
            String address
    ) {
        try {
            String userId = currentUserId();
            UserLocation location = userLocationService.setCurrentLocation(
                    userId,
                    address
            );
            log.info(
                    "[LocationTools] 用户位置已保存: userId={}, city={}, address={}",
                    userId,
                    location.city(),
                    location.address()
            );
            return String.format(
                    "当前位置已设置为：%s（%s，经度 %.6f，纬度 %.6f）",
                    location.address(),
                    location.city(),
                    location.longitude(),
                    location.latitude()
            );
        } catch (Exception e) {
            log.warn("[LocationTools] 保存位置失败: address={}, error={}", address, e.getMessage());
            return "❌ 保存位置失败：" + e.getMessage();
        }
    }

    @Tool(description = "根据用户之前保存的当前位置查询天气。当用户询问天气但没有说城市时调用")
    public String getLocalWeather(
            @ToolParam(
                    description = "base表示当前实时天气，all表示未来几天天气；用户未指定未来天气时传base",
                    required = false
            )
            String type
    ) {
        try {
            return userLocationService.getLocalWeather(
                    currentUserId(),
                    type
            );
        } catch (Exception e) {
            log.warn("[LocationTools] 当前位置天气查询失败: error={}", e.getMessage());
            return "❌ " + e.getMessage();
        }
    }

    @Tool(description = "搜索用户当前位置附近的餐厅、酒店、景点、医院、停车场等地点")
    public String searchNearby(
            @ToolParam(description = "要搜索的地点关键词，例如：咖啡店、停车场、医院")
            String keyword,
            @ToolParam(
                    description = "搜索半径，单位米；用户未指定时传3000",
                    required = false
            )
            Integer radiusMeters
    ) {
        try {
            int radius = radiusMeters == null ? 3_000 : radiusMeters;
            List<PlaceResult> places = userLocationService.searchNearby(
                    currentUserId(),
                    keyword,
                    radius
            );
            if (places.isEmpty()) {
                return "附近没有找到“" + keyword + "”相关地点";
            }

            StringBuilder answer = new StringBuilder("附近搜索结果：\n");
            for (int i = 0; i < places.size(); i++) {
                PlaceResult place = places.get(i);
                answer.append(i + 1)
                        .append(". ")
                        .append(place.name());
                if (!place.address().isBlank()) {
                    answer.append("｜").append(place.address());
                }
                if (place.distanceMeters() > 0) {
                    answer.append("｜约")
                            .append(place.distanceMeters())
                            .append("米");
                }
                answer.append('\n');
            }
            return answer.toString().trim();
        } catch (Exception e) {
            log.warn("[LocationTools] 周边搜索失败: keyword={}, error={}", keyword, e.getMessage());
            return "❌ 附近地点搜索失败：" + e.getMessage();
        }
    }

    @Tool(description = "从用户当前位置到目的地规划步行或驾车路线，仅适用于同一城市内的短途出行；若目的地是其他城市或距离较远、需要坐高铁/火车/地铁/公交时，请改用 planTransitRoute 工具")
    public String planRouteFromCurrentLocation(
            @ToolParam(description = "目的地，例如：杭州东站、西湖断桥")
            String destination,
            @ToolParam(
                    description = "出行方式：walking表示步行，driving表示驾车；用户未指定时传walking",
                    required = false
            )
            String mode
    ) {
        try {
            RouteResult route = userLocationService.planRoute(
                    currentUserId(),
                    destination,
                    mode
            );
            String modeText = "driving".equals(route.mode())
                    ? "驾车"
                    : "步行";
            StringBuilder answer = new StringBuilder();
            answer.append(modeText)
                    .append("路线：约")
                    .append(formatDistance(route.distanceMeters()))
                    .append("，预计")
                    .append(formatDuration(route.durationSeconds()))
                    .append("。\n");

            int stepCount = Math.min(route.steps().size(), 8);
            for (int i = 0; i < stepCount; i++) {
                answer.append(i + 1)
                        .append(". ")
                        .append(route.steps().get(i))
                        .append('\n');
            }
            if (route.steps().size() > stepCount) {
                answer.append("其余路段请以实时导航为准。\n");
            }
            return answer.toString().trim();
        } catch (Exception e) {
            log.warn("[LocationTools] 路线规划失败: destination={}, error={}", destination, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("OVER_DIRECTION_RANGE")) {
                return "❌ 目的地距离过远，步行/驾车无法直达。请改用 planTransitRoute 工具规划公交/地铁/高铁路线。";
            }
            return "❌ 路线规划失败：" + e.getMessage();
        }
    }

    @Tool(description = "规划从用户当前位置到目的地的公共交通综合路线（公交/地铁/高铁/火车），适用于跨城市出行或长途出行，例如去外地、去火车站、目的地距离较远时使用")
    public String planTransitRoute(
            @ToolParam(description = "目的地，例如：北京站、杭州东站、西湖景区")
            String destination,
            @ToolParam(
                    description = "换乘策略：synthesis=综合推荐(默认)，economy=最经济，leastTransfer=少换乘，leastWalk=少步行，metroFirst=地铁优先，fastest=时间短；用户未指定时传synthesis",
                    required = false
            )
            String preference
    ) {
        try {
            int strategy = normalizeStrategy(preference);
            TransitRouteResult result = userLocationService.planTransitRoute(
                    currentUserId(),
                    destination,
                    strategy
            );

            StringBuilder answer = new StringBuilder();//可变字符串构建器，用来逐步拼接最终要返回给模型的文本
            answer.append("从当前位置到").append(destination)
                    .append("的公共交通路线：\n");

            //控制最多只展示 3 个方案，防止返回给模型的内容太长。
            int planCount = Math.min(result.plans().size(), 3);
            for (int i = 0; i < planCount; i++) {
                TransitRouteResult.TransitPlan plan = result.plans().get(i);
                answer.append("方案").append(i + 1).append("：")
                        .append(formatDuration(plan.durationSeconds()))
                        .append("，约")
                        .append(formatDistance(plan.distanceMeters()));
                if (plan.transitFeeYuan() > 0) {
                    answer.append("，票价约")
                            .append(plan.transitFeeYuan())
                            .append("元");
                }
                answer.append('\n');
                for (TransitRouteResult.Segment segment : plan.segments()) {
                    answer.append("  ├─ ");
                    switch (segment.type()) {
                        case "walking" -> answer.append(segment.detail());
                        case "bus" -> answer.append(segment.name())
                                .append("：")
                                .append(segment.detail());
                        case "railway" -> answer.append(segment.name())
                                .append(" ")
                                .append(segment.detail());
                        default -> answer.append(segment.name())
                                .append(" ")
                                .append(segment.detail());
                    }
                    answer.append('\n');
                }
            }
            return answer.toString().trim();
        } catch (Exception e) {
            log.warn("[LocationTools] 公共交通路线规划失败: destination={}, error={}",
                    destination, e.getMessage());
            return "❌ 公共交通路线规划失败：" + e.getMessage();
        }
    }

    //把模型传的"人类语言策略"（preference）翻译成高德接口能识别的数字代号（strategy
    private int normalizeStrategy(String preference) {
        if (preference == null || preference.isBlank()) {
            return 0;
        }
        return switch (preference.trim().toLowerCase()) {
            case "economy" -> 1;
            case "leasttransfer" -> 2;
            case "leastwalk" -> 3;
            case "metrofirst" -> 7;
            case "fastest" -> 8;
            default -> 0;
        };
    }

    private String currentUserId() {
        String userId = userContext.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("无法识别当前微信用户");
        }
        return userId;
    }

    private String formatDistance(int meters) {
        if (meters >= 1_000) {
            return String.format("%.1f公里", meters / 1_000.0);
        }
        return meters + "米";
    }

    private String formatDuration(int seconds) {
        int minutes = Math.max(1, (int) Math.ceil(seconds / 60.0));
        if (minutes >= 60) {
            return (minutes / 60) + "小时" + (minutes % 60) + "分钟";
        }
        return minutes + "分钟";
    }
}
