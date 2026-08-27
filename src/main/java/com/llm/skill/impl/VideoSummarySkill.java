package com.llm.skill.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.skill.BaseSkill;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class VideoSummarySkill extends BaseSkill {

    private static final String SILICONFLOW_API_URL = "https://api.siliconflow.cn/v1";
    private String apiKey = System.getenv("SILICONFLOW_API_KEY");
    // B站视频链接正则
    private static final Pattern BILI_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?bilibili\\.com/video/(BV\\w+)|" +
                    "(?:https?://)?(?:www\\.)?b23\\.tv/(\\w+)"
    );


    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "视频摘要";
    }

    @Override
    public String[] getKeywords() {
        return new String[]{
                "总结视频", "视频摘要", "提取视频内容", "概括视频",
                "这个视频讲了什么", "视频说了什么", "帮我看看这个视频",
                "B站视频", "bilibili", "b23.tv"
        };
    }

    @Override
    protected String doExecute(String userMessage, String userId) {
        log.info("[VideoSummarySkill] 开始处理: {}", userMessage);

        // 1. 提取 B站视频 ID
        String videoId = extractBilibiliId(userMessage);
        if (videoId == null) {
            return "⚠️ 请提供 B站视频链接，例如：\n" +
                    "• https://www.bilibili.com/video/BV1xxxx\n" +
                    "• https://b23.tv/xxxxx";
        }

        log.info("[VideoSummarySkill] 视频ID: {}", videoId);

        try {
            // 2. 先尝试 B站自带 AI 摘要（最快）
            String biliSummary = fetchBilibiliSummary(videoId);
            if (biliSummary != null) {
                return biliSummary;
            }

            log.info("[VideoSummarySkill] B站无 AI 摘要，走 ASR 流程...");

            // 3. 走 ASR 流程：下载音频 → 转文字 → 生成摘要
            return processWithASR(videoId);

        } catch (Exception e) {
            log.error("[VideoSummarySkill] 处理失败", e);
            return "❌ 视频摘要生成失败：" + e.getMessage();
        }
    }

    /**
     * 使用 ASR 处理视频
     */
    private String processWithASR(String videoId) throws Exception {
        String videoTitle = fetchVideoTitle(videoId);
        log.info("[VideoSummarySkill] 视频标题: {}", videoTitle);

        // 1. 下载视频音频（使用 yt-dlp）
        Path audioPath = downloadAudio(videoId);
        log.info("[VideoSummarySkill] 音频下载完成: {}", audioPath);

        // 2. ASR 转文字
        String transcript = asrTranscribe(audioPath);
        log.info("[VideoSummarySkill] ASR 转文字完成，长度: {} 字符", transcript.length());

        if (transcript == null || transcript.isEmpty()) {
            return "⚠️ 音频转文字失败，请检查音频是否清晰或换一个视频。";
        }

        // 3. 用 LLM 生成摘要
        String summary = generateSummary(videoTitle, transcript);
        log.info("[VideoSummarySkill] 摘要生成完成");

        // 4. 清理临时文件
        try {
            Files.deleteIfExists(audioPath);
        } catch (Exception e) {
            log.warn("清理临时文件失败: {}", e.getMessage());
        }

        return formatResult(videoTitle, summary, transcript);
    }

    /**
     * 下载视频音频（使用 yt-dlp）
     */
    private Path downloadAudio(String videoId) throws Exception {
        String videoUrl = "https://www.bilibili.com/video/" + videoId;
        String outputDir = System.getProperty("java.io.tmpdir");
        String outputFile = outputDir + File.separator + "bili_audio_" + UUID.randomUUID().toString().substring(0, 8) + ".mp3";

        // ✅ yt-dlp 完整路径
        String ytDlpPath = "C:\\Users\\24110\\AppData\\Local\\Programs\\Python\\Python311\\Scripts\\yt-dlp.exe";
        String cookiesPath = "D:\\Agent\\cookies.txt";

        String[] cmd = {
                ytDlpPath,
                "--cookies", cookiesPath,
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "5",
                "-o", outputFile,
                videoUrl
        };

        log.info("[VideoSummarySkill] 执行: {}", String.join(" ", cmd));

        Process process = Runtime.getRuntime().exec(cmd);

        // ✅ 读取错误输出
        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
                log.error("yt-dlp error: {}", line);
            }
        }

        // ✅ 读取标准输出
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("yt-dlp output: {}", line);
            }
        }

        int exitCode = process.waitFor();
        log.info("yt-dlp exit code: {}", exitCode);

        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp 执行失败，退出码: " + exitCode + "\n错误: " + errorOutput);
        }

        // 检查生成的文件
        Path audioPath = Paths.get(outputFile);
        if (!Files.exists(audioPath)) {
            audioPath = Paths.get(outputFile + ".mp3");
        }
        if (!Files.exists(audioPath)) {
            throw new RuntimeException("音频文件未生成: " + audioPath);
        }

        log.info("[VideoSummarySkill] 音频下载完成: {}, 大小: {} KB",
                audioPath, Files.size(audioPath) / 1024);
        return audioPath;
    }

    /**
     * ASR 语音转文字（调用 SiliconFlow SenseVoiceSmall）
     */
    private String asrTranscribe(Path audioPath) throws Exception {
        // 读取音频文件
        byte[] audioBytes = Files.readAllBytes(audioPath);
        log.info("[VideoSummarySkill] 音频大小: {} KB", audioBytes.length / 1024);

        // 构建 multipart 请求
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioPath.getFileName().toString(),
                        RequestBody.create(audioBytes, MediaType.parse("audio/mpeg")))
                .addFormDataPart("model", "FunAudioLLM/SenseVoiceSmall")
                .build();

        Request request = new Request.Builder()
                .url(SILICONFLOW_API_URL + "/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            log.info("[VideoSummarySkill] ASR 响应状态: {}", response.code());

            if (!response.isSuccessful()) {
                log.error("ASR 失败: {}", responseBody);
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String text = root.path("text").asText();

            if (text == null || text.isEmpty()) {
                return null;
            }

            return text;
        }
    }

    /**
     * 调用 LLM 生成摘要
     */
    private String generateSummary(String title, String transcript) throws Exception {
        String prompt = String.format("""
                你是一个视频内容总结助手。请根据以下视频转录文字，生成简洁、有条理的摘要。

                视频标题：%s

                转录文字：
                %s

                请按以下格式输出：
                1. 一句话概括（20字以内）
                2. 核心内容（3-5个要点）
                3. 适合谁看（可选）
                """, title, transcript.length() > 4000 ? transcript.substring(0, 4000) + "..." : transcript);

        String json = String.format("""
                {
                    "model": "Qwen/Qwen2.5-7B-Instruct",
                    "messages": [
                        {"role": "system", "content": "你是一个专业的视频内容总结助手，擅长提炼核心信息。"},
                        {"role": "user", "content": "%s"}
                    ],
                    "max_tokens": 800
                }
                """, prompt.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));

        Request request = new Request.Builder()
                .url(SILICONFLOW_API_URL + "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                log.error("LLM 失败: {}", responseBody);
                return "摘要生成失败，请稍后重试。";
            }

            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").path(0).path("message").path("content").asText();
        }
    }

    /**
     * 格式化最终结果
     */
    private String formatResult(String title, String summary, String transcript) {
        return String.format("""
                📹 视频摘要

                📌 标题：%s

                📝 %s

                📊 转录文字长度：%d 字符
                """, title, summary, transcript.length());
    }

    // ==================== B站 API 辅助方法 ====================

    /**
     * 从消息中提取 B站视频 ID
     */
    private String extractBilibiliId(String text) {
        Matcher matcher = BILI_PATTERN.matcher(text);
        if (matcher.find()) {
            String bvid = matcher.group(1);
            if (bvid != null) {
                return bvid;
            }
            return matcher.group(2);
        }
        return null;
    }

    /**
     * 获取视频标题
     */
    private String fetchVideoTitle(String videoId) throws Exception {
        String url = String.format(
                "https://api.bilibili.com/x/web-interface/view?bvid=%s",
                videoId
        );

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "未知标题";
            }

            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);

            if (root.path("code").asInt() != 0) {
                return "未知标题";
            }

            return root.path("data").path("title").asText();
        }
    }

    /**
     * 调用 B站 API 获取视频 AI 摘要（如果有）
     */
    private String fetchBilibiliSummary(String videoId) {
        try {
            String cid = fetchCid(videoId);
            if (cid == null) {
                return null;
            }

            String summaryUrl = String.format(
                    "https://api.bilibili.com/x/web-interface/view/conclusion/get?bvid=%s&cid=%s",
                    videoId, cid
            );

            Request request = new Request.Builder()
                    .url(summaryUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                if (root.path("code").asInt() != 0) {
                    return null;
                }

                String summary = root.path("data").path("model_result").path("summary").asText();
                if (summary == null || summary.isEmpty()) {
                    return null;
                }

                return "📹 B站视频摘要\n\n📝 " + summary;
            }

        } catch (Exception e) {
            log.warn("获取 B站摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 B站视频的 cid
     */
    private String fetchCid(String bvid) {
        try {
            String url = String.format(
                    "https://api.bilibili.com/x/web-interface/view?bvid=%s",
                    bvid
            );

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                if (root.path("code").asInt() != 0) {
                    return null;
                }

                JsonNode pages = root.path("data").path("pages");
                if (pages.isArray() && pages.size() > 0) {
                    return pages.get(0).path("cid").asText();
                }

                return null;
            }

        } catch (Exception e) {
            log.warn("获取 cid 失败: {}", e.getMessage());
            return null;
        }
    }
}