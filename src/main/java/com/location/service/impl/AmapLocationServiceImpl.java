package com.location.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.location.dto.LocationResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.service.AmapLocationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class AmapLocationServiceImpl
        implements AmapLocationService {

    private static final String GEOCODE_URL =
            "https://restapi.amap.com/v3/geocode/geo"
                    + "?key={key}&address={address}";
    private static final String NEARBY_SEARCH_URL =
            "https://restapi.amap.com/v3/place/around"
                    + "?key={key}&location={location}&keywords={keyword}"
                    + "&radius={radius}&offset=5&page=1&extensions=base";
    private static final String WALKING_ROUTE_URL =
            "https://restapi.amap.com/v3/direction/walking"
                    + "?key={key}&origin={origin}&destination={destination}";
    private static final String DRIVING_ROUTE_URL =
            "https://restapi.amap.com/v3/direction/driving"
                    + "?key={key}&origin={origin}&destination={destination}"
                    + "&strategy=0&extensions=base";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gaode.key}")
    private String apiKey;

    @Override
    public LocationResult geocode(String address) {
        if (address == null || address.isBlank()) {
            throw new RuntimeException("地址不能为空");
        }

        String cleanedAddress = address.trim();

        try {
            String json = restTemplate.getForObject(
                    GEOCODE_URL,
                    String.class,
                    apiKey,
                    cleanedAddress
            );

            JsonNode root = objectMapper.readTree(json);

            if (!"1".equals(root.path("status").asString())) {
                String info = root.path("info").asString();
                throw new RuntimeException("高德地址解析失败：" + info);
            }

            JsonNode geocodes = root.path("geocodes");

            if (!geocodes.isArray() || geocodes.isEmpty()) {
                throw new RuntimeException("未找到地址：" + cleanedAddress);
            }

            JsonNode geocode = geocodes.get(0);

            String coordinateText =
                    geocode.path("location").asString();

            String[] coordinates = coordinateText.split(",");

            if (coordinates.length != 2) {
                throw new RuntimeException("高德返回的经纬度格式不正确");
            }

            double longitude =
                    Double.parseDouble(coordinates[0].trim());

            double latitude =
                    Double.parseDouble(coordinates[1].trim());

            String formattedAddress =
                    geocode.path("formatted_address").asString();

            String province =
                    geocode.path("province").asString();

            String city =
                    geocode.path("city").asString();

            String district =
                    geocode.path("district").asString();

            if (city == null || city.isBlank()) {
                city = province;
            }

            LocationResult result = new LocationResult(
                    formattedAddress,
                    province,
                    city,
                    district,
                    longitude,
                    latitude
            );

            log.info(
                    "[AmapLocation] 地址解析成功: address={}, city={}, longitude={}, latitude={}",
                    cleanedAddress,
                    city,
                    longitude,
                    latitude
            );

            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "[AmapLocation] 地址解析异常: address={}, error={}",
                    cleanedAddress,
                    e.getMessage(),
                    e
            );
            throw new RuntimeException("地址解析失败：" + e.getMessage());
        }
    }

    @Override
    public List<PlaceResult> searchNearby(
            double longitude,
            double latitude,
            String keyword,
            int radiusMeters
    ) {
        if (keyword == null || keyword.isBlank()) {
            throw new RuntimeException("搜索关键词不能为空");
        }

        int safeRadius = Math.max(100, Math.min(radiusMeters, 50_000));
        String location = coordinate(longitude, latitude);

        try {
            String json = restTemplate.getForObject(
                    NEARBY_SEARCH_URL,
                    String.class,
                    apiKey,
                    location,
                    keyword.trim(),
                    safeRadius
            );
            JsonNode root = readSuccessfulResponse(json, "附近地点搜索");

            List<PlaceResult> results = new ArrayList<>();
            JsonNode pois = root.path("pois");
            if (!pois.isArray()) {
                return results;
            }

            for (JsonNode poi : pois) {
                double[] point = parseCoordinate(poi.path("location").asString());
                results.add(new PlaceResult(
                        nodeText(poi.path("name")),
                        nodeText(poi.path("address")),
                        nodeText(poi.path("type")),
                        parseInt(nodeText(poi.path("distance"))),
                        point[0],
                        point[1]
                ));
            }

            log.info(
                    "[AmapLocation] 周边搜索成功: keyword={}, radius={}, count={}",
                    keyword,
                    safeRadius,
                    results.size()
            );
            return results;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "[AmapLocation] 周边搜索异常: keyword={}, error={}",
                    keyword,
                    e.getMessage(),
                    e
            );
            throw new RuntimeException("附近地点搜索失败：" + e.getMessage());
        }
    }

    @Override
    public RouteResult planRoute(
            double originLongitude,
            double originLatitude,
            double destinationLongitude,
            double destinationLatitude,
            String mode
    ) {
        String normalizedMode = normalizeMode(mode);
        String url = "driving".equals(normalizedMode)
                ? DRIVING_ROUTE_URL
                : WALKING_ROUTE_URL;
        String origin = coordinate(originLongitude, originLatitude);
        String destination = coordinate(
                destinationLongitude,
                destinationLatitude
        );

        try {
            String json = restTemplate.getForObject(
                    url,
                    String.class,
                    apiKey,
                    origin,
                    destination
            );
            JsonNode root = readSuccessfulResponse(json, "路线规划");
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                throw new RuntimeException("高德没有返回可用路线");
            }

            JsonNode path = paths.get(0);
            List<String> instructions = new ArrayList<>();
            JsonNode steps = path.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    String instruction = nodeText(step.path("instruction"));
                    if (!instruction.isBlank()) {
                        instructions.add(instruction);
                    }
                }
            }

            RouteResult result = new RouteResult(
                    normalizedMode,
                    parseInt(nodeText(path.path("distance"))),
                    parseInt(nodeText(path.path("duration"))),
                    List.copyOf(instructions)
            );
            log.info(
                    "[AmapLocation] 路线规划成功: mode={}, distance={}, duration={}",
                    normalizedMode,
                    result.distanceMeters(),
                    result.durationSeconds()
            );
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "[AmapLocation] 路线规划异常: mode={}, error={}",
                    normalizedMode,
                    e.getMessage(),
                    e
            );
            throw new RuntimeException("路线规划失败：" + e.getMessage());
        }
    }

    private JsonNode readSuccessfulResponse(String json, String action) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !"1".equals(root.path("status").asString())) {
                String info = root == null ? "返回内容为空" : root.path("info").asString();
                throw new RuntimeException("高德" + action + "失败：" + info);
            }
            return root;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析高德响应失败：" + e.getMessage());
        }
    }

    private double[] parseCoordinate(String value) {
        String[] parts = value == null ? new String[0] : value.split(",");
        if (parts.length != 2) {
            throw new RuntimeException("高德返回的经纬度格式不正确");
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim())
            };
        } catch (NumberFormatException e) {
            throw new RuntimeException("高德返回的经纬度不是有效数字");
        }
    }

    private String coordinate(double longitude, double latitude) {
        return longitude + "," + latitude;
    }

    private String normalizeMode(String mode) {
        String value = mode == null
                ? "walking"
                : mode.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()
                || value.contains("步行")
                || value.contains("walk")) {
            return "walking";
        }
        if (value.contains("驾车")
                || value.contains("开车")
                || value.contains("drive")
                || value.contains("car")) {
            return "driving";
        }
        throw new RuntimeException("目前只支持步行或驾车路线");
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            return !node.isEmpty() ? node.get(0).asString() : "";
        }
        return node.asString();
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}