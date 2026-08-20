package com.wxbot;

import tools.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.processor.MessageProcessor;
import com.processor.ProcessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class WeixinBotService {

    private static final Path SESSION_FILE = Paths.get("work", "ilink-session.json");

    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;

    private ILinkClient client;
    private final ExecutorService taskExecutor;
    private final ScheduledExecutorService senderScheduler;
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final Map<String, Long> lastSendTime = new ConcurrentHashMap<>();
    private static final long MIN_SEND_INTERVAL_MS = 2_000L;
    private static final long RETRY_DELAY_MS = 2_000L;

    private volatile boolean running = true;

    public WeixinBotService(ObjectMapper objectMapper, MessageProcessor messageProcessor) {
        this.objectMapper = objectMapper;
        this.messageProcessor = messageProcessor;
        this.taskExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "wx-task-worker");
            t.setDaemon(true);
            return t;
        });
        this.senderScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "wx-sender");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        try {
            Files.createDirectories(SESSION_FILE.getParent());
        } catch (IOException e) {
            log.error("创建 session 目录失败", e);
        }

        Thread startupThread = new Thread(this::initLogin, "wx-bot-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        taskExecutor.shutdownNow();
        senderScheduler.shutdownNow();
        closeClient();
        log.info("[WeixinBotService] 已安全关闭");
    }

    private void initLogin() {
        // ✅ 先删除旧的 Session 文件，强制重新扫码
        deleteSession();
        log.info("已删除旧 Session，强制重新扫码登录");

        ResumeContext resumeContext = loadSession();
        if (resumeContext != null) {
            try {
                client = ILinkClient.builder()
                        .onLogin(new OnLoginListener() {
                            @Override
                            public void onLoginSuccess(LoginContext ctx) {
                                log.info("Session 恢复成功: botId={}", ctx.getBotId());
                            }
                            @Override
                            public void onLoginFailure(Throwable throwable) {
                                log.error("Session 恢复失败: {}", throwable.getMessage());
                            }
                        })
                        .resumeContext(resumeContext)
                        .build();
                startPolling();
                return;
            } catch (Exception e) {
                log.warn("Session 恢复异常，尝试重新扫码登录", e);
                deleteSession();
            }
        }

        try {
            client = ILinkClient.builder()
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext ctx) {
                            log.info("微信登录成功: botId={}", ctx.getBotId());
                            saveSession(client.exportResumeContext());
                            startPolling();
                        }
                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("微信登录失败: {}", throwable.getMessage());
                        }
                    })
                    .build();

            log.info("⏳ 正在获取二维码...");
            String qrCodeContent = client.executeLogin();
            log.info("qrCodeContent 长度: {}", qrCodeContent != null ? qrCodeContent.length() : 0);

            if (qrCodeContent == null || qrCodeContent.isEmpty()) {
                log.error("❌ 获取二维码失败：内容为空");
                return;
            }

            // ✅ 保存二维码为图片文件
            try {
                String base64Data = qrCodeContent;
                if (base64Data.contains(",")) {
                    base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
                }
                base64Data = base64Data.replaceAll("\\s", "");
                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                Path qrPath = Paths.get("qrcode.png");
                Files.write(qrPath, imageBytes);
                log.info("✅ 二维码已保存到: {}", qrPath.toAbsolutePath());
            } catch (Exception e) {
                log.error("保存二维码失败: {}", e.getMessage());
                log.info("二维码内容前200字符: {}", qrCodeContent.substring(0, Math.min(200, qrCodeContent.length())));
            }

            log.info("==========================================");
            log.info("请扫描二维码登录微信，二维码已保存为 qrcode.png");
            log.info("==========================================");

            // ✅ 等待扫码登录
            LoginContext context = client.getLoginFuture().get();
            log.info("✅ 扫码登录成功: botId={}", context.getBotId());

        } catch (Exception e) {
            log.error("初始化微信登录失败", e);
        }
    }

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return;

        Thread pollThread = new Thread(() -> {
            log.info("消息轮询线程已启动");
            try {
                while (running) {
                    try {
                        ILinkClient currentClient = client;
                        if (currentClient == null) break;

                        List<WeixinMessage> messages = currentClient.getUpdates();
                        if (messages != null && !messages.isEmpty()) {
                            saveSession(currentClient.exportResumeContext());
                            for (WeixinMessage message : messages) {
                                handleMessage(message);
                            }
                        }
                    } catch (SessionExpiredException e) {
                        log.warn("会话过期: {}", e.getMessage());
                        deleteSession();
                        break;
                    } catch (Exception e) {
                        log.error("消息轮询异常", e);
                        sleep();
                    }
                }
            } finally {
                pollingStarted.set(false);
                log.info("消息轮询线程已停止");
            }
        }, "wx-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }


    private void handleMessage(WeixinMessage msg) {
        log.info("📩 收到微信消息: from={}, msgId={}", msg.getFrom_user_id(), msg.getMessage_id());
        String userId = msg.getFrom_user_id();

        taskExecutor.submit(() -> {
            try {
                ProcessResult result = messageProcessor.process(msg, client);
                if (result != null) {
                    sendResult(result);
                }
            } catch (Exception e) {
                log.error("处理消息失败", e);
                safeSendText(userId, "⚠️ 处理您的请求时出错了");
            }
        });
    }

    private void sendResult(ProcessResult result) {
        if (result == null) return;
        String userId = result.userId();

        // 兼容不同的返回类型处理
        if (result.type() != null) {
            switch (result.type()) {
                case IMAGE -> safeSendImage(userId, result.data());
                case VOICE -> safeSendVoice(userId, result.data());
                case TEXT -> safeSendText(userId, result.text());
            }
        } else if (result.text() != null) {
            safeSendText(userId, result.text());
        }
    }

    public void safeSendText(String userId, String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        long sendAt = lastSendTime.compute(userId, (k, last) -> Math.max(now, last == null ? now : last + MIN_SEND_INTERVAL_MS));
        long delayMs = sendAt - now;
        if (delayMs > 0) {
            senderScheduler.schedule(() -> doSendText(userId, text), delayMs, TimeUnit.MILLISECONDS);
        } else {
            doSendText(userId, text);
        }
    }

    private void doSendText(String userId, String text) {
        try {
            if (client != null) client.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送文本失败: userId={}", userId, e);
        }
    }

    private void safeSendImage(String userId, byte[] imageData) {
        try { if (client != null && imageData != null) client.sendImage(userId, imageData, "image.png", ""); } catch (Exception e) { log.error("发送图片失败", e); }
    }

    private void safeSendVoice(String userId, byte[] voiceData) {
        try { if (client != null && voiceData != null) client.sendFile(userId, voiceData, "voice.mp3", null); } catch (Exception e) { log.error("发送语音失败", e); }
    }

    private record SessionData(String botToken, String userId, String botId, String baseUrl, String updatesCursor) {}

    private void saveSession(ResumeContext resumeContext) {
        if (resumeContext == null || resumeContext.getLoginContext() == null) return;
        LoginContext login = resumeContext.getLoginContext();
        SessionData data = new SessionData(login.getBotToken(), login.getUserId(), login.getBotId(), login.getBaseUrl(), resumeContext.getUpdatesCursor());
        try {
            Files.writeString(SESSION_FILE, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存 session 失败: {}", e.getMessage());
        }
    }

    private ResumeContext loadSession() {
        if (!Files.exists(SESSION_FILE)) return null;
        try {
            String json = Files.readString(SESSION_FILE, StandardCharsets.UTF_8);
            SessionData data = objectMapper.readValue(json, SessionData.class);
            LoginContext loginContext = new LoginContext(data.botToken(), data.userId(), data.botId(), data.baseUrl());
            return ResumeContext.builder(loginContext).updatesCursor(data.updatesCursor()).build();
        } catch (Exception e) {
            deleteSession();
            return null;
        }
    }

    private void deleteSession() {
        try { Files.deleteIfExists(SESSION_FILE); } catch (IOException ignored) {}
    }

    private void closeClient() {
        ILinkClient current = client;
        client = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) {}
        }
    }

    private void sleep() {
        try { Thread.sleep(WeixinBotService.RETRY_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}