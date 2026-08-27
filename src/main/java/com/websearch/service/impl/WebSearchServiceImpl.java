package com.websearch.service.impl;

import com.websearch.config.WebSearchProperties;
import com.websearch.dto.WebSearchResult;
import com.websearch.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WebSearchServiceImpl implements WebSearchService {

    private static final String ENDPOINT = "https://serpapi.com/search";

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebSearchServiceImpl(
            WebSearchProperties properties,
            ObjectMapper objectMapper) {

        this.properties = properties;
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public List<WebSearchResult> search(String query) {

        if (!properties.isEnabled()) {
            throw new RuntimeException("联网搜索功能尚未启用");
        }

        if (query == null || query.isBlank()) {
            throw new RuntimeException("搜索内容不能为空");
        }

        if (properties.getApiKey() == null
                || properties.getApiKey().isBlank()) {

            throw new RuntimeException("联网搜索 API Key 未配置");
        }

        long start = System.currentTimeMillis();

        try {
            String encodedQuery = URLEncoder.encode(
                    query.trim(), StandardCharsets.UTF_8);
            String url = ENDPOINT
                    + "?engine=" + properties.getEngine()
                    + "&q=" + encodedQuery
                    + "&api_key=" + properties.getApiKey()
                    + "&num=" + properties.getMaxResults();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(
                            properties.getTimeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                log.warn(
                        "[WebSearch] HTTP请求失败: status={}",
                        response.statusCode()
                );

                throw new RuntimeException(
                        "联网搜索请求失败，HTTP状态码："
                                + response.statusCode()
                );
            }

            JsonNode root =
                    objectMapper.readTree(response.body());

            checkApiError(root);

            List<WebSearchResult> results =
                    parseResults(root, properties.getMaxResults());

            long elapsed =
                    System.currentTimeMillis() - start;

            log.info(
                    "[WebSearch] 搜索完成: query={}, count={}, elapsed={}ms",
                    query,
                    results.size(),
                    elapsed
            );

            return results;

        } catch (RuntimeException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException("联网搜索请求被中断");

        } catch (Exception e) {
            long elapsed =
                    System.currentTimeMillis() - start;

            log.error(
                    "[WebSearch] 搜索异常: query={}, elapsed={}ms, error={}",
                    query,
                    elapsed,
                    e.getMessage(),
                    e
            );

            throw new RuntimeException("联网搜索暂时不可用");
        }
    }

    private void checkApiError(JsonNode root) {

        JsonNode error = root.path("error");

        if (!error.isMissingNode()
                && !error.isNull()) {

            String message = error.asText("");

            log.warn(
                    "[WebSearch] SerpAPI返回错误: message={}",
                    message
            );

            throw new RuntimeException(
                    "联网搜索失败：" + message
            );
        }
    }

    private List<WebSearchResult> parseResults(
            JsonNode root,
            int maxResults) {

        JsonNode organicResults =
                root.path("organic_results");

        if (!organicResults.isArray()) {
            return List.of();
        }

        List<WebSearchResult> results =
                new ArrayList<>();

        int count = Math.min(
                organicResults.size(),
                maxResults
        );

        for (int i = 0; i < count; i++) {

            JsonNode item =
                    organicResults.get(i);

            results.add(new WebSearchResult(
                    item.path("title").asText(""),
                    item.path("link").asText(""),
                    item.path("snippet").asText(""),
                    "",
                    item.path("source").asText(""),
                    item.path("date").asText("")
            ));
        }

        return results;
    }
}
