package com.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.processor.ProcessResult;
import com.processor.UserContext;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.util.Queue;

/**
 * 语音合成工具，通过通用 Spring AI {@link TextToSpeechModel} 调用第三方平台 TTS，
 * 并使用纯 Java 音频解码 + JavaCV Silk 录制器转码为微信标准的 Silk 格式后入队发送。
 */
@Slf4j
@Component
@SuppressWarnings("unused")
public class VoiceTools {

    private final TextToSpeechModel speechModel;
    private final UserContext userContext;
    private final Queue<ProcessResult> voiceQueue;
    private final String voiceId;
    private final String maleVoiceId;

    public VoiceTools(TextToSpeechModel speechModel,
                      UserContext userContext,
                      Queue<ProcessResult> voiceQueue,
                      @Value("${spring.ai.openai.tts.voice-id:default}") String voiceId,
                      @Value("${spring.ai.openai.tts.male-voice-id:default}") String maleVoiceId) {
        this.speechModel = speechModel;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
        this.voiceId = voiceId;
        this.maleVoiceId = maleVoiceId;
    }

    /**
     * 调用 TTS 将文字合成为原始音频，转码为 Silk 后入队，由 {@code MessageProcessor} 消费发送。
     *
     * @param text   要朗读的文字内容
     * @param gender 音色性别，{@code "male"} 为男声，{@code "female"} 或为空为女声
     * @return 合成成功返回 {@code "语音已播报"}，失败返回错误提示
     */
    @Tool(description = "用语音朗读文字。当用户要求用语音回答、朗读、播报、读出来时调用此工具")
    public String speak(
            @ToolParam(description = "要朗读的文字内容") String text,
            @ToolParam(description = "语音性别：male(男声) 或 female(女声)。用户未指定时传 female", required = false)
            String gender
    ) {
        String userId = userContext.getCurrentUserId();
        log.info("[VoiceTools] 被调用: text={}, userId={}, gender={}",
                text.length() > 100 ? text.substring(0, 100) + "..." : text, userId, gender);

        String selectedVoiceId = resolveVoiceId(gender);

        try {
            // 1. 通过通用 Prompt 发起 TTS 调用
            TextToSpeechPrompt prompt = new TextToSpeechPrompt(text);

            // 2. 拿到第三方 TTS 生成的原始音频字节
            byte[] rawAudio = speechModel.call(prompt)
                    .getResult().getOutput();

            log.info("[VoiceTools] TTS 原始音频生成成功: size={}KB", rawAudio.length / 1024);

            byte[] silkAudio = convertToSilk(rawAudio);

            voiceQueue.add(ProcessResult.voice(silkAudio, userId));

            log.info("[VoiceTools] 语音合成与转码全部完成: userId={}, voiceId={}", userId, selectedVoiceId);
            return "语音已播报";
        } catch (Exception e) {
            log.error("[VoiceTools] 语音合成失败: userId={}, error={}", userId, e.getMessage(), e);
            return "❌ 语音合成失败：" + e.getMessage();
        }
    }

    /**
     * 使用 Java 原生音频流读取 MP3 并转码为微信 Silk 格式
     */
    private byte[] convertToSilk(byte[] rawAudio) {
        File tempOutput = null;
        try {
            tempOutput = File.createTempFile("silk_output_", ".silk");

            // 通过 Java 标准音频 SPI 读取 MP3 字节并转换为标准 PCM 音频流
            try (AudioInputStream mp3Stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(rawAudio));
                 AudioInputStream pcmStream = AudioSystem.getAudioInputStream(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, mp3Stream)) {

                javax.sound.sampled.AudioFormat baseFormat = pcmStream.getFormat();
                int sampleRate = (int) baseFormat.getSampleRate();
                if (sampleRate <= 0) {
                    sampleRate = 24000;
                }

                // 初始化 JavaCV Silk 录制器
                try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(tempOutput, 1)) {
                    recorder.setFormat("silk");
                    recorder.setAudioCodecName("silk");
                    recorder.setSampleRate(sampleRate);
                    recorder.setAudioChannels(1);
                    recorder.setAudioBitrate(16000);
                    recorder.start();

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = pcmStream.read(buffer)) != -1) {
                        int nSamples = bytesRead / 2;
                        short[] samples = new short[nSamples];
                        ByteBuffer.wrap(buffer, 0, bytesRead)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(samples);

                        Frame frame = new Frame(1, nSamples, 1, 2);
                        ((ShortBuffer) frame.image[0]).put(samples);
                        recorder.record(frame);
                    }
                    recorder.stop();
                }
            }

            byte[] silkBytes = Files.readAllBytes(tempOutput.toPath());
            log.info("[VoiceTools] 音频成功转码为 Silk 格式: size={}KB", silkBytes.length / 1024);
            return silkBytes;

        } catch (Exception e) {
            log.error("[VoiceTools] 音频转码 Silk 异常，降级返回原音频: error={}", e.getMessage(), e);
            return rawAudio;
        } finally {
            if (tempOutput != null && tempOutput.exists()) {
                boolean ignored = tempOutput.delete();
            }
        }
    }

    private String resolveVoiceId(String gender) {
        if ("male".equalsIgnoreCase(gender)) {
            return maleVoiceId;
        }
        return voiceId;
    }
}