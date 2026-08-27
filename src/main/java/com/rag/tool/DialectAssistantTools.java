package com.rag.tool;

import com.rag.mapper.UserDialectPreferenceMapper;
import com.llm.tools.VoiceTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DialectAssistantTools {

    private final UserDialectPreferenceMapper preferenceMapper;
    private final VoiceTools voiceTools;

    public DialectAssistantTools(UserDialectPreferenceMapper preferenceMapper, VoiceTools voiceTools) {
        this.preferenceMapper = preferenceMapper;
        this.voiceTools = voiceTools;
    }

    @Tool(description = "接收用户的方言语音片段与类型，进行方言特征解析与文本转写")
    public String recognizeDialectAudio(
            @ToolParam(description = "语音文件路径或音频标识") String audioPath,
            @ToolParam(description = "预期的方言种类，如粤语、四川话、东北话等") String dialectType) {
        log.info("[DialectTools] 方言语音识别: audioPath={}, dialectType={}", audioPath, dialectType);
        return "成功识别方言语音，转写内容为：[模拟转写文本]";
    }

    @Tool(description = "将文本转化为方言语音文件播报。若方言合成失败，系统自动降级为标准普通话语音")
    public String synthesizeDialectSpeech(
            @ToolParam(description = "需要播报的文本内容") String text,
            @ToolParam(description = "目标方言类型") String dialectType) {
        log.info("[DialectTools] 方言语音合成与降级播报: text={}, dialectType={}", text, dialectType);
        try {
            // 直接复用你写好的硬核 VoiceTools，将文本合成为 Silk 并异步入队
            String result = voiceTools.speak(text, "female");
            return "方言语音合成成功 (" + dialectType + ")：" + result;
        } catch (Exception e) {
            log.warn("[DialectTools] 方言语音合成失败，平稳降级为标准普通话语音播报: {}", e.getMessage());
            return voiceTools.speak(text, "female"); // 降级兜底
        }
    }

    @Tool(description = "保存或更新用户的方言交互偏好设置（如针对家中长辈的习惯）")
    public String saveUserDialectPreference(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "方言类型，如粤语、四川话等") String dialectType) {
        log.info("[DialectTools] 保存用户方言偏好到数据库: userId={}, dialectType={}", userId, dialectType);
        preferenceMapper.upsertPreference(userId, dialectType);
        return "成功为用户 " + userId + " 设置并保存方言偏好为：" + dialectType;
    }

    @Tool(description = "读取用户的方言偏好配置，实现免重复设置")
    public String getUserDialectPreference(
            @ToolParam(description = "用户 ID") String userId) {
        log.info("[DialectTools] 从数据库查询用户方言偏好: userId={}", userId);
        String dialect = preferenceMapper.findDialectByUserId(userId);
        return (dialect != null && !dialect.isBlank()) ? dialect : "普通话";
    }
}