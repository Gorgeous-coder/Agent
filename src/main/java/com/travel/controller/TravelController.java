package com.travel.controller;

import com.travel.dto.TravelPlanData;
import com.travel.service.TravelPlannerAgent;
import com.travel.service.TravelMapGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelPlannerAgent travelPlannerAgent;
    private final TravelMapGenerator travelMapGenerator;

    /**
     * ⭐ 核心接口：用户提问 → AI生成行程 → 返回HTML地图
     *
     * 用户访问：/travel/generate?destination=北京&days=3&preferences=美食
     */
    @GetMapping(value = "/generate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateTravelMap(
            @RequestParam String destination,
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(required = false) String preferences) {

        log.info("[Travel] 用户请求生成行程: destination={}, days={}, preferences={}",
                destination, days, preferences);

        try {
            // Step 1: AI 生成行程数据（含联网搜索）
            TravelPlanData planData = travelPlannerAgent.generatePlan(destination, days, preferences);

            // Step 2: 渲染为 HTML 地图
            String html = travelMapGenerator.generate(planData);

            return ResponseEntity.ok(html);

        } catch (Exception e) {
            log.error("[Travel] 生成失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("<h2>生成失败</h2><p>" + e.getMessage() + "</p>");
        }
    }

    /**
     * 返回 JSON 格式数据（供前端自己渲染）
     */
    @GetMapping(value = "/generate/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TravelPlanData> generateTravelPlanJson(
            @RequestParam String destination,
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(required = false) String preferences) {

        TravelPlanData planData = travelPlannerAgent.generatePlan(destination, days, preferences);
        return ResponseEntity.ok(planData);
    }
}