package com.group26.Agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.model.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeChatBotService {

    private ILinkClient client;
    private boolean running = true;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WeatherService weatherService;

    private static final String API_KEY = "sk-dcbdzibgmqcfarorzdffjjlhrnopblnokshkyqzgakupjonw";

    // ==================== SiliconFlow 文本对话 ====================
    private String callSiliconFlowText(String userMessage) {
        try {
            // ✅ 检测是否问关于语音能力的问题，如果是，直接回复正面回答
            if (userMessage.contains("你能发语音吗") || userMessage.contains("你会说话吗")
                    || userMessage.contains("能语音吗") || userMessage.contains("语音回复")) {
                return "当然可以！我已经为你生成了语音回复，请查收语音消息。";
            }

            // ✅ 如果是打招呼要求语音，也直接回复
            if (userMessage.contains("打个招呼") || userMessage.contains("说句话")
                    || userMessage.contains("用语音")) {
                return "你好！我是你的智能语音助手，很高兴为你服务。请问有什么我可以帮你的吗？";
            }

            String json = String.format("""
            {
                "model": "Qwen/Qwen2.5-7B-Instruct",
                "messages": [
                    {"role": "system", "content": "你是一个微信语音助手，用户可能会要求你用语音回复。当用户要求语音时，直接给出简短清晰的回复内容即可，不要说'我无法发语音'这样的话。"},
                    {"role": "user", "content": "%s"}
                ],
                "max_tokens": 300
            }
            """, userMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.siliconflow.cn/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String reply = root.path("choices").path(0).path("message").path("content").asText();
                reply = reply.replaceAll("(?s)<think>.*?</think>", "").trim();

                // ✅ 如果回复太短或为空，给默认回复
                if (reply == null || reply.isEmpty() || reply.length() < 2) {
                    reply = "你好，我是你的语音助手，有什么可以帮你的吗？";
                }
                return reply;
            } else {
                return "调用失败: " + response.statusCode();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "调用异常: " + e.getMessage();
        }
    }

    // ==================== SiliconFlow TTS ====================
    private byte[] callSiliconFlowTTS(String text) {
        try {
            // ✅ 修复：更彻底的文本清洗
            String cleanText = text
                    .replaceAll("(?s)<think>.*?</think>", "")  // 移除 think 标签
                    .replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9，。！？、：；（）,.!?\\s]", " ")  // 保留中英文和标点
                    .replaceAll("\\s+", " ")  // 合并空格
                    .trim();

            // ✅ 如果清洗后为空，用默认问候语
            if (cleanText.isEmpty() || cleanText.length() < 2) {
                cleanText = "你好，我是你的智能助手，很高兴为你服务。";
            }

            // ✅ 限制长度，避免太长导致 TTS 出错
            if (cleanText.length() > 200) {
                cleanText = cleanText.substring(0, 200) + "。";
            }

            System.out.println("📝 TTS 文本: " + cleanText);

            String json = String.format("""
        {
            "model": "FunAudioLLM/CosyVoice2-0.5B",
            "voice": "FunAudioLLM/CosyVoice2-0.5B:alex",
            "input": "%s",
            "response_format": "wav"
        }
        """, cleanText.replace("\"", "\\\""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.siliconflow.cn/v1/audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                byte[] audioData = response.body();
                System.out.println("✅ SiliconFlow TTS 成功，音频大小: " + audioData.length + " 字节");
                return audioData;
            } else {
                System.err.println("❌ SiliconFlow TTS 调用失败: " + response.statusCode());
                return null;
            }

        } catch (Exception e) {
            System.err.println("❌ SiliconFlow TTS 异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String callSiliconFlowImage(String userMessage, byte[] imageBytes) {
        try {
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
            String dataUrl = "data:image/jpeg;base64," + base64Image;

            // ✅ 使用 Qwen3-VL-8B-Instruct
            String json = String.format("""
            {
                "model": "Qwen/Qwen3-VL-8B-Instruct",
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "%s"},
                            {"type": "image_url", "image_url": "%s"}
                        ]
                    }
                ],
                "max_tokens": 500
            }
            """,
                    userMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"),
                    dataUrl
            );

            System.out.println("🌐 调用 SiliconFlow Qwen3-VL-8B 识别图片...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.siliconflow.cn/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("📡 响应状态码: " + response.statusCode());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String reply = root.path("choices").path(0).path("message").path("content").asText();
                return reply != null && !reply.isEmpty() ? reply : "识别结果为空";
            } else {
                String errorBody = response.body();
                System.err.println("❌ 错误详情: " + errorBody);

                if (response.statusCode() == 403) {
                    return "⚠️ 请先在 SiliconFlow 控制台开通 Qwen/Qwen3-VL-8B-Instruct 模型";
                }
                if (response.statusCode() == 400 && errorBody.contains("Model does not exist")) {
                    return "⚠️ 模型不存在，请在 SiliconFlow 控制台确认该模型是否已开通";
                }
                return "图片识别失败，状态码: " + response.statusCode();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "图片识别异常: " + e.getMessage();
        }
    }

    // ==================== 意图识别 ====================
    private String detectIntent(String content) {
        // 天气相关
        String[] weatherKeywords = {"天气", "下雨", "温度", "冷", "热", "气温", "预报", "刮风", "台风", "暴雨", "晴天", "多云", "阴天"};
        for (String keyword : weatherKeywords) {
            if (content.contains(keyword)) {
                return "WEATHER";
            }
        }

        // ✅ 语音相关 - 更全面
        String[] voiceKeywords = {
                "语音", "声音", "说", "讲", "朗读", "读出来", "播报", "念",
                "打招呼", "说句话", "说一句", "讲一句", "回复语音",
                "用语音", "语音回复", "语音说", "语音讲"
        };
        for (String keyword : voiceKeywords) {
            if (content.contains(keyword)) {
                return "VOICE";
            }
        }

        // ✅ 匹配 "给我说" "帮我念" 等模式
        if (content.matches(".*[给我|帮我].*[说|讲|念].*")) {
            return "VOICE";
        }

        // 图片相关
        String[] imageKeywords = {"图片", "照片", "图像", "看", "识别"};
        for (String keyword : imageKeywords) {
            if (content.contains(keyword)) {
                return "IMAGE";
            }
        }

        return "CHAT";
    }

    // ==================== 生成语音回复内容 ====================
    private String generateVoiceReply(String userMessage) {
        // 打招呼类
        if (userMessage.contains("打招呼") || userMessage.contains("你好")
                || userMessage.contains("嗨") || userMessage.contains("hello")) {
            return "你好！我是你的智能语音助手，很高兴认识你，有什么我可以帮你的吗？";
        }

        // 问名字
        if (userMessage.contains("你叫什么") || userMessage.contains("你是谁")) {
            return "我是你的智能语音助手，你可以叫我小助手，很高兴为你服务。";
        }

        // 问能力
        if (userMessage.contains("你能做什么") || userMessage.contains("你会什么")) {
            return "我可以帮你查询天气、回答问题、陪你聊天，还能用语音回复你哦。";
        }

        // 问天气相关语音
        if (userMessage.contains("天气") || userMessage.contains("温度")) {
            return "好的，正在为你查询天气信息，请稍等。";
        }

        // 默认问候
        return "你好！我是你的智能语音助手，很高兴为你服务。请问有什么我可以帮你的吗？";
    }

    // ==================== 从消息中提取城市名 ====================
    private String extractCity(String content) {
        // 先移除常见的干扰词，让提取更干净
        String cleanContent = content
                .replaceAll("今天|明天|昨天|天气|气温|温度|热不热|冷不冷|怎么样|多少", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // 再尝试从清理后的内容中提取城市名（2-4个中文字符）
        Pattern pattern = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})");
        Matcher matcher = pattern.matcher(cleanContent);
        if (matcher.find()) {
            String city = matcher.group(1);
            // 排除常见的非城市词
            String[] exclude = {"什么", "怎么", "现在", "多少", "那边"};
            for (String ex : exclude) {
                if (city.equals(ex)) {
                    return null;
                }
            }
            return city;
        }

        // 如果清理后提取不到，尝试从原始内容中按特定模式提取
        Pattern[] patterns = {
                Pattern.compile("([\\u4e00-\\u9fa5]{2,4})天气"),
                Pattern.compile("天气([\\u4e00-\\u9fa5]{2,4})"),
                Pattern.compile("([\\u4e00-\\u9fa5]{2,4})今天"),
                Pattern.compile("今天([\\u4e00-\\u9fa5]{2,4})"),
                Pattern.compile("([\\u4e00-\\u9fa5]{2,4})热不热"),
                Pattern.compile("([\\u4e00-\\u9fa5]{2,4})冷不冷")
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(content);
            if (m.find()) {
                return m.group(1);
            }
        }

        // 如果都匹配不到，返回 null
        return null;
    }

    // ==================== 初始化 ====================
    @PostConstruct
    public void init() {
        System.out.println("======= iLink 微信机器人启动 =======");

        new Thread(() -> {
            try {
                ILinkClientBuilder builder = new ILinkClientBuilder();
                client = builder.build();

                System.out.println("请扫描二维码登录...");
                String qrcodeBase64 = client.executeLogin();

                try {
                    String base64Data = qrcodeBase64;
                    if (base64Data.contains(",")) {
                        base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
                    }
                    base64Data = base64Data.replaceAll("\\s", "");
                    byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                    String desktopPath = System.getProperty("user.home") + "/Desktop/qrcode.png";
                    Files.write(java.nio.file.Paths.get(desktopPath), imageBytes);
                    System.out.println("✅ 二维码已保存到桌面: " + desktopPath);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("⚠️ 二维码原始数据前200字符: " + qrcodeBase64.substring(0, Math.min(200, qrcodeBase64.length())));
                }

                while (!client.isLoggedIn()) {
                    System.out.println("⏳ 等待扫码确认...");
                    Thread.sleep(2000);
                }
                System.out.println("✅ 登录成功！");

                System.out.println("📨 开始接收消息...");
                while (running) {
                    try {
                        List<WeixinMessage> messages = client.getUpdates();
                        if (messages != null && !messages.isEmpty()) {
                            for (WeixinMessage msg : messages) {
                                handleMessage(msg);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ 获取消息异常: " + e.getMessage());
                        if (e.getMessage() != null && e.getMessage().contains("not logged in")) {
                            System.out.println("⚠️ 重新登录...");
                            client.executeLogin();
                            while (!client.isLoggedIn()) {
                                Thread.sleep(2000);
                            }
                        }
                    }
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                System.err.println("❌ 机器人运行异常: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== 处理消息 ====================
    private void handleMessage(WeixinMessage msg) {
        try {
            String fromUser = msg.getFrom_user_id();

            if (msg.getItem_list() != null && !msg.getItem_list().isEmpty()) {
                MessageItem item = msg.getItem_list().get(0);

                if (item.getType() == 1) {
                    TextItem textItem = item.getText_item();
                    String content = textItem != null ? textItem.getText() : "";
                    System.out.println("收到文本: " + content);

                    String intent = detectIntent(content);
                    System.out.println("识别的意图: " + intent);

                    // ===== 天气查询 =====
                    if ("WEATHER".equals(intent)) {
                        String city = extractCity(content);
                        System.out.println("查询城市: " + city);
                        String weatherInfo = weatherService.getWeather(city);
                        client.sendText(fromUser, weatherInfo);
                        System.out.println("✅ 已回复天气信息");
                        return;
                    }

                    // ===== 语音请求检测 =====
                    boolean wantVoice = "VOICE".equals(intent)
                            || content.contains("语音")
                            || content.contains("声音")
                            || content.contains("说")
                            || content.contains("讲")
                            || content.contains("念")
                            || content.contains("朗读")
                            || content.contains("播报")
                            || content.contains("打招呼")
                            || content.contains("说句话")
                            || content.matches(".*[给我|帮我].*[说|讲|念].*");

                    // 获取 AI 回复
                    String reply = callSiliconFlowText(content);
                    System.out.println("SiliconFlow 回复: " + reply);

                    // ✅ 如果用户要语音，强制使用语音友好的简短回复
                    if (wantVoice) {
                        // 先发提示
                        client.sendText(fromUser, "🎤 好的，正在生成语音...");
                        System.out.println("✅ 已回复文字提示");

                        // ✅ 强制使用简短清晰的语音回复（覆盖 AI 可能的不当回复）
                        String voiceReply = generateVoiceReply(content);
                        System.out.println("🔊 语音回复内容: " + voiceReply);

                        try {
                            byte[] wavData = callSiliconFlowTTS(voiceReply);
                            if (wavData != null && wavData.length > 0) {
                                String contextToken = msg.getContext_token();
                                client.sendFile(fromUser, wavData, "语音消息.wav", contextToken);
                                System.out.println("✅ 语音文件已发送");
                            } else {
                                // TTS 失败，发送文字
                                client.sendText(fromUser, voiceReply);
                                System.out.println("⚠️ TTS 失败，已发送文字");
                            }
                        } catch (Exception e) {
                            System.err.println("⚠️ 语音发送失败: " + e.getMessage());
                            client.sendText(fromUser, voiceReply);
                        }
                    } else {
                        client.sendText(fromUser, reply);
                        System.out.println("✅ 已回复文字");
                    }

                } else if (item.getType() == 2) {
                    // ========== 图片消息 ==========
                    System.out.println("======= 收到图片消息 =======");
                    ImageItem imageItem = item.getImage_item();
                    if (imageItem != null) {
                        CDNMedia media = imageItem.getMedia();
                        if (media != null) {
                            try {
                                byte[] imageBytes = client.downloadMedia(media);
                                System.out.println("图片大小: " + imageBytes.length + " 字节");

                                // ✅ 改用 SiliconFlow + Qwen VL 识别图片
                                String userPrompt = "请描述这张图片的内容，包括颜色、物体、场景等。用中文回答。";
                                String reply = callSiliconFlowImage(userPrompt, imageBytes);
                                System.out.println("识别结果: " + reply);

                                client.sendText(fromUser, reply);
                                System.out.println("✅ 已回复");
                            } catch (Exception e) {
                                System.out.println("处理图片失败: " + e.getMessage());
                                client.sendText(fromUser, "[图片消息] 处理失败，请重试");
                            }
                        }
                    }

                } else if (item.getType() == 3) {
                    System.out.println("======= 收到语音消息 =======");
                    VoiceItem voiceItem = item.getVoice_item();
                    if (voiceItem != null) {
                        String content = voiceItem.getText();

                        // 如果第一次获取为空，等待1秒后重试一次
                        if (content == null || content.isEmpty()) {
                            System.out.println("⏳ 语音转文字结果为空，1秒后重试...");
                            try {
                                Thread.sleep(1000);
                                content = voiceItem.getText(); // 重新获取
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        if (content == null || content.isEmpty()) {
                            content = "[语音消息，转文字失败]";
                        } else {
                            System.out.println("语音转文字: " + content);
                        }

                        String intent = detectIntent(content);
                        if ("WEATHER".equals(intent)) {
                            String city = extractCity(content);
                            String weatherInfo = weatherService.getWeather(city);
                            client.sendText(fromUser, weatherInfo);
                            System.out.println("✅ 已回复天气信息");
                            return;
                        }

                        String reply = callSiliconFlowText(content);
                        System.out.println("SiliconFlow 回复: " + reply);
                        client.sendText(fromUser, reply);
                        System.out.println("✅ 已回复文字");
                    } else {
                        System.out.println("VoiceItem 为 null");
                        client.sendText(fromUser, "[语音消息，解析失败]");
                    }

                } else {
                    client.sendText(fromUser, "[暂不支持该消息类型: " + item.getType() + "]");
                }
            }

        } catch (Exception e) {
            System.err.println("处理消息异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}