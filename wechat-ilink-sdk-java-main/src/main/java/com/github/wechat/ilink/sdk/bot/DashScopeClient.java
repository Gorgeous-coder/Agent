package com.github.wechat.ilink.sdk.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（DashScope）原生能力客户端。
 *
 * <p>覆盖 OpenAI 兼容接口覆盖不到的 AI 能力：
 * <ul>
 *   <li>通义万相文生图：wanx2.1-t2i-turbo（异步任务 + 轮询）</li>
 *   <li>CosyVoice 文字转语音（HTTP 同步调用）</li>
 * </ul>
 *
 * <p>注意：这些模型需要在阿里云百炼控制台单独开通后才可调用。
 */
public class DashScopeClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 任务轮询间隔与上限 */
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long POLL_TIMEOUT_MS = 120_000;

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String imageModel;
    private final String ttsModel;
    private final String ttsVoice;

    public DashScopeClient(String apiKey, String imageModel, String ttsModel, String ttsVoice) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey;
        this.imageModel = imageModel;
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
    }

    // ==================== 文生图 ====================

    /**
     * 通义万相文生图，返回生成的图片字节（PNG/JPEG）。
     *
     * @param prompt 图片描述
     * @return 图片二进制内容
     */
    public byte[] generateImage(String prompt) throws IOException {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IOException("画图 prompt 为空");
        }
        String taskId = submitImageTask(prompt.trim());
        String imageUrl = waitTaskUrl(taskId, "image");
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new IOException("文生图任务未返回图片地址，taskId=" + taskId);
        }
        return downloadBytes(imageUrl);
    }

    private String submitImageTask(String prompt) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", imageModel);
        body.putObject("input").put("prompt", prompt);
        ObjectNode params = body.putObject("parameters");
        params.put("size", "1024*1024");
        params.put("n", 1);

        JsonNode resp = postJson(
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
                body,
                "X-DashScope-Async", "enable");
        JsonNode taskId = resp.path("output").path("task_id");
        if (taskId.isMissingNode()) {
            throw new IOException("文生图提交失败，未返回 task_id: " + resp);
        }
        return taskId.asText();
    }

    // ==================== 文字转语音（TTS） ====================

    /**
     * CosyVoice 文字转语音，返回音频字节（MP3）。
     *
     * @param text 要朗读的文本
     * @return 音频二进制内容
     */
    public byte[] textToSpeech(String text) throws IOException {
        return textToSpeech(text, ttsVoice);
    }

    /**
     * CosyVoice 文字转语音（指定音色，覆盖构造时的默认音色），返回音频字节（MP3）。
     *
     * @param text  要朗读的文本
     * @param voice 音色 id（如 longanyang / longxiaochun / longchen），null 或空则用默认
     * @return 音频二进制内容
     */
    public byte[] textToSpeech(String text, String voice) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("TTS 文本为空");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", ttsModel);
        ObjectNode input = body.putObject("input");
        input.put("text", text.trim());
        String voiceId = (voice == null || voice.trim().isEmpty()) ? ttsVoice : voice.trim();
        if (voiceId != null && !voiceId.isEmpty()) {
            input.put("voice", voiceId);
        }
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        JsonNode resp = postJson(
                "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
                body,
                null, null);
        String audioUrl = resp.path("output").path("audio").path("url").asText("");
        if (audioUrl == null || audioUrl.isEmpty()) {
            throw new IOException("TTS 未返回音频地址: " + resp);
        }
        System.out.println("TTS 合成成功（音色 " + (voiceId == null ? "默认" : voiceId)
                + "），音频 URL: " + truncate(audioUrl, 120));
        return downloadBytes(audioUrl);
    }

    // ==================== 任务轮询 ====================

    /** 轮询异步任务直到成功，返回结果里的第一个 url。 */
    private String waitTaskUrl(String taskId, String kind) throws IOException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(kind + "任务轮询被打断", e);
            }
            JsonNode resp = getJson("https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId);
            JsonNode output = resp.path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                // 结果可能在 results[].url 或 audio 字段
                JsonNode results = output.path("results");
                if (results.isArray() && results.size() > 0) {
                    String url = results.get(0).path("url").asText("");
                    if (!url.isEmpty()) {
                        return url;
                    }
                }
                String audio = output.path("audio").asText("");
                if (!audio.isEmpty()) {
                    return audio;
                }
                String url = output.path("url").asText("");
                if (!url.isEmpty()) {
                    return url;
                }
                throw new IOException(kind + "任务成功但未找到结果地址: " + resp);
            }
            if ("FAILED".equalsIgnoreCase(status)
                    || "CANCELED".equalsIgnoreCase(status)
                    || "UNKNOWN".equalsIgnoreCase(status)) {
                String msg = output.path("message").asText("");
                throw new IOException(kind + "任务失败, 状态=" + status
                        + (msg.isEmpty() ? "" : ", 原因=" + msg));
            }
            // PENDING / RUNNING 继续等
        }
        throw new IOException(kind + "任务轮询超时(" + (POLL_TIMEOUT_MS / 1000) + "s)，taskId=" + taskId);
    }

    private byte[] downloadBytes(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载媒体失败: HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    // ==================== HTTP 工具 ====================

    private JsonNode postJson(String url, ObjectNode body, String extraHeader, String extraValue)
            throws IOException {
        RequestBody requestBody = RequestBody.create(MAPPER.writeValueAsString(body), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(requestBody);
        if (extraHeader != null && !extraHeader.isEmpty()) {
            builder.header(extraHeader, extraValue);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("百炼 API 请求失败: HTTP " + response.code()
                        + " - " + truncate(respBody, 500));
            }
            return MAPPER.readTree(respBody);
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("百炼任务查询失败: HTTP " + response.code()
                        + " - " + truncate(respBody, 500));
            }
            return MAPPER.readTree(respBody);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
