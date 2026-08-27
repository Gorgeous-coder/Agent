package com.location.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.location.dto.GeocodeResult;
import com.location.dto.PlaceResult;
import com.location.dto.RouteResult;
import com.location.dto.TransitRouteResult;
import com.location.service.AmapLocationService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class AmapLocationServiceImpl
        implements AmapLocationService {//Amap = A‑Map，就是 高德地图

    private static final String PLACE_SEARCH_URL =
            "https://restapi.amap.com/v3/place/text?key={key}&keywords={keyword}&city={city}&citylimit=true&offset=1&page=1&extensions=base";

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
    private static final String TRANSIT_ROUTE_URL =
            "https://restapi.amap.com/v3/direction/transit/integrated"
                    + "?key={key}&origin={origin}&destination={destination}"
                    + "&city={city}&cityd={cityd}&strategy={strategy}";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gaode.key}")
    private String apiKey;

    @Override
    public GeocodeResult geocode(String address) {
        if (address == null || address.isBlank()) {
            throw new RuntimeException("地址不能为空");
        }
        log.info("[AmapLocation] 使用的 Key: {}", apiKey);
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

            String citycode =
                    geocode.path("citycode").asString();

            String district =
                    geocode.path("district").asString();

            if (city == null || city.isBlank()) {
                city = province;
            }

            GeocodeResult result = new GeocodeResult(
                    formattedAddress,
                    province,
                    city,
                    citycode,
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

    /**
     * 关键字搜索景点（比地理编码更智能）
     */
    @Override
    public GeocodeResult searchPlace(String keyword, String city) {
        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());

            String url = PLACE_SEARCH_URL
                    .replace("{key}", apiKey)
                    .replace("{keyword}", encodedKeyword)
                    .replace("{city}", encodedCity);

            log.info("[AmapLocation] 关键字搜索: keyword={}, city={}", keyword, city);

            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);

            if (!"1".equals(root.path("status").asString())) {
                String info = root.path("info").asString();
                log.warn("[AmapLocation] 关键字搜索失败: {} - {}", keyword, info);
                return null;
            }

            JsonNode pois = root.path("pois");
            if (pois.isArray() && pois.size() > 0) {
                JsonNode poi = pois.get(0);
                String location = poi.path("location").asString();
                if (location == null || location.isBlank()) {
                    return null;
                }
                String[] coords = location.split(",");
                if (coords.length != 2) {
                    return null;
                }
                return new GeocodeResult(
                        poi.path("name").asString(),
                        poi.path("pname").asString(),
                        poi.path("cityname").asString(),
                        poi.path("adcode").asString(),
                        Double.parseDouble(coords[0]),
                        Double.parseDouble(coords[1])
                );
            }
            return null;
        } catch (Exception e) {
            log.warn("[AmapLocation] 关键字搜索异常: {} - {}", keyword, e.getMessage());
            return null;
        }
    }


    @Override
    public TransitRouteResult planTransitRoute(
            double originLongitude,
            double originLatitude,
            double destinationLongitude,
            double destinationLatitude,
            String citycode1,
            String citycode2,
            int strategy
    ) {
        int safeStrategy = normalizeStrategy(strategy);
        String origin = coordinate(originLongitude, originLatitude);
        String destination = coordinate(
                destinationLongitude,
                destinationLatitude
        );
        String originCitycode = citycode1 == null || citycode1.isBlank() ? "" : citycode1.trim();
        String destCitycode = citycode2 == null || citycode2.isBlank() ? "" : citycode2.trim();

        try {
            String json = restTemplate.getForObject(//restTemplate是 Spring 自带的"通用 HTTP 请求工具"
                    TRANSIT_ROUTE_URL,
                    String.class,
                    apiKey,
                    origin,
                    destination,
                    originCitycode,
                    destCitycode,
                    safeStrategy
            );
            JsonNode root = readSuccessfulResponse(json, "公交路径规划");//设置异常时打印的接口名称，这样异常一路抛到工具层打日志时，一眼就能看出是哪个接口挂了，不用猜
            JsonNode transits = root.path("route").path("transits");
            if (!transits.isArray() || transits.isEmpty()) {
                throw new RuntimeException("高德没有返回可用换乘方案");
            }
            // 临时调试：打印第一个方案的真实结构，确认 duration/distance 字段位置
            log.info("[AmapLocation] 公交规划调试 transits[0] 结构: {}", transits.get(0).toString());

            List<TransitRouteResult.TransitPlan> plans = new ArrayList<>();
            for (JsonNode transit : transits) {
                int duration = parseInt(nodeText(transit.path("duration")));
                int distance = parseInt(nodeText(transit.path("distance")));
                int fee = parseInt(nodeText(transit.path("cost")));

                List<TransitRouteResult.Segment> segments = new ArrayList<>();
                JsonNode segmentNodes = transit.path("segments");
                if (segmentNodes.isArray()) {
                    for (JsonNode segment : segmentNodes) {
                        segments.addAll(parseSegment(segment));
                    }
                }
                plans.add(new TransitRouteResult.TransitPlan(
                        duration,
                        distance,
                        fee,
                        List.copyOf(segments)
                ));
            }

            log.info(
                    "[AmapLocation] 公交路径规划成功: strategy={}, plans={}",
                    safeStrategy,
                    plans.size()
            );
            return new TransitRouteResult(List.copyOf(plans));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "[AmapLocation] 公交路径规划异常: strategy={}, error={}",
                    safeStrategy,
                    e.getMessage(),
                    e
            );
            throw new RuntimeException("公交路径规划失败：" + e.getMessage());
        }
    }

    /**
     * 解析公交方案中的一段行程，可能拆成 0~2 个 Segment：
     * 步行段（walking）、地铁/公交段（bus）、高铁段（railway）。
     */
    private List<TransitRouteResult.Segment> parseSegment(JsonNode segment) {
        List<TransitRouteResult.Segment> result = new ArrayList<>();

        JsonNode walking = segment.path("walking");
        if (walking.isObject() && !walking.isNull()) {
            String distance = nodeText(walking.path("distance"));
            if (!distance.isBlank()) {
                result.add(new TransitRouteResult.Segment(
                        "walking",
                        "步行",
                        "步行 " + distance + " 米"
                ));
            }
        }

        JsonNode busLines = segment.path("bus").path("buslines");
        if (busLines.isArray()) {
            for (JsonNode busLine : busLines) {
                String lineName = nodeText(busLine.path("name"));
                String departStop = nodeText(
                        busLine.path("departure_stop").path("name")
                );
                String arrivalStop = nodeText(
                        busLine.path("arrival_stop").path("name")
                );
                if (!lineName.isBlank()) {
                    result.add(new TransitRouteResult.Segment(
                            "bus",
                            lineName,
                            departStop + " → " + arrivalStop
                    ));
                }
            }
        }

        JsonNode railway = segment.path("railway");
        if (railway.isObject() && !railway.isNull()) {
            String trip = nodeText(railway.path("trip"));
            String departName = nodeText(
                    railway.path("departure_stop").path("name")
            );
            String arrivalName = nodeText(
                    railway.path("arrival_stop").path("name")
            );
            String departTime = nodeText(
                    railway.path("departure_stop").path("time")
            );
            String arrivalTime = nodeText(
                    railway.path("arrival_stop").path("time")
            );
            if (!trip.isBlank()) {
                result.add(new TransitRouteResult.Segment(
                        "railway",
                        trip + " 次" + (railwayType(railway)),
                        departName + " " + formatTime(departTime)
                                + " → " + arrivalName + " " + formatTime(arrivalTime)
                ));
            }
        }

        return result;
    }

    /**
     * 高德车次类型转中文：2011=高铁，2012=动车，2013=城际，2014=直达特快，其余=火车。
     */
    private String railwayType(JsonNode railway) {
        String type = nodeText(railway.path("type"));
        return switch (type) {
            case "2011" -> "高铁";
            case "2012" -> "动车";
            case "2013" -> "城际";
            case "2014" -> "直达特快";
            default -> "火车";
        };
    }

    /**
     * 高德时间格式 HHmm，可能大于 24 表示跨天，转成 HH:mm 展示。
     */
    private String formatTime(String time) {
        if (time == null || time.isBlank()) {
            return "";
        }
        String t = time.trim();
        if (t.length() < 4) {
            return t;
        }
        int minutes = Integer.parseInt(t) % 1440;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * v3 公交接口的 strategy 只支持 0/1/2/3/5；v5 的 7(地铁优先)/8(时间短) 回退到 0(最快捷)。
     */
    private int normalizeStrategy(int strategy) {
        if (strategy == 7 || strategy == 8) {
            return 0;
        }
        if (strategy < 0 || strategy > 5) {
            return 0;
        }
        return strategy;
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