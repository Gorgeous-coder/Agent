package com.travel.controller;

import com.travel.dto.TravelPlanData;
import com.travel.service.MockDataService;
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
public class TravelMapController {

    private final MockDataService mockDataService;
    private final TravelMapGenerator travelMapGenerator;

    @GetMapping(value = "/map", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateMockMap(
            @RequestParam(defaultValue = "上海") String destination,
            @RequestParam(defaultValue = "3") int days) {
        log.info("[TravelMap] 使用 mock 数据生成路线图: destination={}, days={}", destination, days);
        TravelPlanData data = mockDataService.generateMockData(destination, days);
        String html = travelMapGenerator.generate(data);
        return ResponseEntity.ok(html);
    }

    @PostMapping(value = "/map", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateMapFromData(@RequestBody TravelPlanData data) {
        log.info("[TravelMap] 接收外部数据生成路线图: destination={}", data.getDestination());
        String html = travelMapGenerator.generate(data);
        return ResponseEntity.ok(html);
    }
}
