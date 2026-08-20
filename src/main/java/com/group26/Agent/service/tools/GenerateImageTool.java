package com.group26.Agent.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Component
public class GenerateImageTool implements Tool {

    private static final String DASHSCOPE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

    // 通义万相 API Key（从环境变量读取）
    private static final String API_KEY = System.getenv("BAILIAN_API_KEY");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "GenerateImage";
    }

    @Override
    public String description() {
        return "根据文字描述生成图片。当用户要求画图、生成图片、创作图像时使用。参数为图片描述文字。";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode promptProp = mapper.createObjectNode();
        promptProp.put("type", "string");
        promptProp.put("description", "图片描述，如：'一只可爱的橘猫坐在窗台上'");
        properties.set("prompt", promptProp);

        schema.set("properties", properties);

        ObjectNode required = mapper.createObjectNode();
        schema.set("required", mapper.createArrayNode().add("prompt"));

        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            // 解析参数
            JsonNode args = MAPPER.readTree(arguments);
            String prompt = args.path("prompt").asText();

            if (prompt == null || prompt.isEmpty()) {
                return "❌ 请提供图片描述";
            }

            System.out.println("🎨 开始生成图片，提示词: " + prompt);

            // 1. 提交任务
            String taskId = submitTask(prompt);
            System.out.println("📤 任务已提交，task_id: " + taskId);

            // 2. 轮询结果
            String imageUrl = pollTask(taskId);
            System.out.println("📥 图片生成完成: " + imageUrl);

            // 3. 下载图片
            byte[] imageBytes = downloadImage(imageUrl);

            // 4. 保存到本地（可选的调试功能）
            // 这里返回图片字节数组的 Base64，让调用者处理
            String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
            return "IMAGE:" + base64;

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 生成图片失败: " + e.getMessage();
        }
    }

    private String submitTask(String prompt) throws IOException {
        // 构建请求体
        HashMap<String, Object> input = new HashMap<>();
        input.put("prompt", prompt);

        HashMap<String, Object> params = new HashMap<>();
        params.put("size", "1024*1024");
        params.put("n", 1);

        HashMap<String, Object> body = new HashMap<>();
        body.put("model", "wanx2.1-t2i-turbo");
        body.put("input", input);
        body.put("parameters", params);

        String json = MAPPER.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(DASHSCOPE_URL)
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String respBody = response.body().string();
            JsonNode root = MAPPER.readTree(respBody);
            return root.path("output").path("task_id").asText();
        }
    }

    private String pollTask(String taskId) throws IOException, InterruptedException {
        for (int i = 0; i < 90; i++) {  // 最多等待 180 秒
            Thread.sleep(2000);

            Request request = new Request.Builder()
                    .url("https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId)
                    .header("Authorization", "Bearer " + API_KEY)
                    .build();

            try (Response response = HTTP.newCall(request).execute()) {
                String respBody = response.body().string();
                JsonNode root = MAPPER.readTree(respBody);
                String status = root.path("output").path("task_status").asText();

                System.out.println("⏳ 任务状态: " + status);

                if ("SUCCEEDED".equals(status)) {
                    return root.path("output").path("results").get(0).path("url").asText();
                } else if ("FAILED".equals(status)) {
                    String msg = root.path("output").path("message").asText();
                    throw new RuntimeException("生成失败: " + msg);
                }
            }
        }
        throw new RuntimeException("生成超时");
    }

    private byte[] downloadImage(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP.newCall(request).execute()) {
            return response.body().bytes();
        }
    }

    /**
     * 从工具执行结果中提取图片字节数组
     * 因为 execute() 返回的是 "IMAGE:" + base64
     */
    public static byte[] extractImageBytes(String toolResult) {
        if (toolResult != null && toolResult.startsWith("IMAGE:")) {
            String base64 = toolResult.substring(6);
            return java.util.Base64.getDecoder().decode(base64);
        }
        return null;
    }
}