package com.travel.controller;

import com.travel.dto.TravelPlanData;
import com.travel.service.MockDataService;
import com.travel.service.TravelMapGenerator;
import com.travel.service.TravelPlannerAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/travel")
@RequiredArgsConstructor
public class TravelMapController {

    private final MockDataService mockDataService;
    private final TravelMapGenerator travelMapGenerator;
    private final TravelPlannerAgent travelPlannerAgent;

    // ============================================================
    // 页面入口
    // ============================================================

    @GetMapping(value = "/map", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> showMapPage() {
        log.info("[TravelMap] 返回地图页面");
        try {
            // 直接返回静态 HTML 模板
            String html = travelMapGenerator.loadTemplate();
            return ResponseEntity.ok(html);
        } catch (Exception e) {
            log.error("[TravelMap] 加载模板失败: {}", e.getMessage());
            return ResponseEntity.ok("<h2>加载失败</h2><p>" + e.getMessage() + "</p>");
        }
    }

    // ============================================================
    // Mock 数据接口（测试用）
    // ============================================================

    @GetMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TravelPlanData> generatePlan(
            @RequestParam(defaultValue = "南京") String destination,
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(required = false) String preferences) {
        log.info("[TravelMap] 生成行程计划: destination={}, days={}, preferences={}",
                destination, days, preferences);
        // 先尝试 AI，失败则降级到 Mock
        try {
            TravelPlanData data = travelPlannerAgent.generatePlan(destination, days, preferences);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.warn("[TravelMap] AI 生成失败，降级到 Mock: {}", e.getMessage());
            TravelPlanData data = mockDataService.generateMockDataWithRoutes(destination, days);
            return ResponseEntity.ok(data);
        }
    }

    // ============================================================
    // AI 接口
    // ============================================================

    @GetMapping(value = "/ai/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TravelPlanData> generateAIPlan(
            @RequestParam(defaultValue = "南京") String destination,
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(required = false) String preferences) {
        log.info("[TravelMap] AI 生成行程数据: destination={}, days={}, preferences={}",
                destination, days, preferences);
        TravelPlanData data = travelPlannerAgent.generatePlan(destination, days, preferences);
        return ResponseEntity.ok(data);
    }

    @GetMapping(value = "/ai/map", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateAIMap(
            @RequestParam(defaultValue = "南京") String destination,
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(required = false) String preferences) {
        log.info("[TravelMap] AI 生成路线图: destination={}, days={}, preferences={}",
                destination, days, preferences);
        TravelPlanData data = travelPlannerAgent.generatePlan(destination, days, preferences);
        String html = travelMapGenerator.generate(data);
        return ResponseEntity.ok(html);
    }

    // ============================================================
    // 测试接口
    // ============================================================

    @GetMapping(value = "/plan/with-routes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TravelPlanData> generatePlanWithRoutes(
            @RequestParam(defaultValue = "南京") String destination,
            @RequestParam(defaultValue = "3") int days) {
        log.info("[TravelMap] 生成带路线的 Mock 数据: destination={}, days={}", destination, days);
        TravelPlanData data = mockDataService.generateMockDataWithRoutes(destination, days);
        return ResponseEntity.ok(data);
    }

    @GetMapping(value = "/plan/shanghai-full", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TravelPlanData> generateShanghaiFull() {
        log.info("[TravelMap] 生成上海完整行程数据（含路线）");
        TravelPlanData data = mockDataService.generateMockShanghaiDataWithRoutes();
        return ResponseEntity.ok(data);
    }
}