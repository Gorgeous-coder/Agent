package com.travel.service;

import com.travel.dto.TravelPlanData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class TravelMapGenerator {

    private final ObjectMapper objectMapper;
    private final String amapKey;

    private static final String TEMPLATE_PATH = "static/travel-map-template.html";

    public TravelMapGenerator(
            ObjectMapper objectMapper,
            @Value("${gaode.js-key:0e7a0d2baaaad0a062909205fa5b82f4}") String amapKey
    ) {
        this.objectMapper = objectMapper;
        this.amapKey = amapKey;
    }

    public String generate(TravelPlanData data) {
        try {
            String template = loadTemplate();
            String json = objectMapper.writeValueAsString(data);
            String title = data.getDestination() + "旅游路线图";

            return template
                    .replace("__TRAVEL_DATA__", json)
                    .replace("__AMAP_KEY__", amapKey)
                    .replace("__TITLE__", title);

        } catch (Exception e) {
            log.error("[TravelMap] HTML 生成失败: {}", e.getMessage(), e);
            return generateErrorPage(e.getMessage());
        }
    }

    // ⭐ 新增：加载模板（供页面入口使用）
    public String loadTemplate() throws IOException {
        return loadTemplateContent();
    }

    private String loadTemplateContent() throws IOException {
        try (InputStream is = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String generateErrorPage(String error) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<title>路线图生成失败</title></head><body>" +
                "<h2>路线图生成失败</h2><p>" + error + "</p>" +
                "</body></html>";
    }
}