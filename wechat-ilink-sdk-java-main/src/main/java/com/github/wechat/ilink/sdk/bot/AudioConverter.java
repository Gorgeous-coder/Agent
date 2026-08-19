package com.github.wechat.ilink.sdk.bot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 音频格式转换工具（依赖外部 ffmpeg / ffprobe / rust-silk）。
 *
 * <p>微信 iLink 语音消息实际传输的是 SILK 格式（encode_type=6）。
 * CosyVoice 等 TTS 服务通常返回 MP3/WAV，因此需要：
 * <ol>
 *   <li>ffmpeg 把 MP3/WAV 转成 16kHz 单声道 PCM WAV</li>
 *   <li>rust-silk 把 WAV 编码成 SILK</li>
 * </ol>
 * </p>
 */
public class AudioConverter {

    /** 转换结果 */
    public static class SilkAudio {
        public final byte[] bytes;
        public final int playTimeMs;
        public final int sampleRate;

        public SilkAudio(byte[] bytes, int playTimeMs, int sampleRate) {
            this.bytes = bytes;
            this.playTimeMs = playTimeMs;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * 微信 iLink 语音的 SILK 采样率。
     *
     * <p>微信收到的语音消息（入站）采样率是 24000Hz（协议示例也是 sample_rate=24000），
     * 因此 TTS 音频转 SILK 时必须也用 24000Hz，否则 iLink 服务端会拒识/丢弃语音消息。
     */
    private static final int SILK_SAMPLE_RATE = 24000;

    private final String ffmpeg;
    private final String ffprobe;
    private final String rustSilk;

    /**
     * @param ffmpegPath  ffmpeg 可执行文件路径；为空时自动从 PATH / 常见路径 / 项目 tools 目录查找
     * @param ffprobePath ffprobe 可执行文件路径；为空时自动查找
     * @param rustSilkPath rust-silk 可执行文件路径；为空时自动查找
     */
    public AudioConverter(String ffmpegPath, String ffprobePath, String rustSilkPath) {
        this.ffmpeg = findExecutable(ffmpegPath, "ffmpeg");
        this.ffprobe = findExecutable(ffprobePath, "ffprobe");
        this.rustSilk = findExecutable(rustSilkPath, "rust-silk");
    }

    public boolean available() {
        return ffmpeg != null && ffprobe != null && rustSilk != null;
    }

    /**
     * 用 ffprobe 探测音频时长（毫秒）。
     *
     * @param inputAudio 音频字节（如 MP3）
     * @param inputExt   扩展名（mp3 / wav），用于生成临时文件
     * @return 时长毫秒；探测失败时返回 0
     */
    public int probeDurationMs(byte[] inputAudio, String inputExt) {
        if (ffprobe == null) {
            System.err.println("[AudioConverter] 未找到 ffprobe，无法探测时长");
            return 0;
        }
        if (inputAudio == null || inputAudio.length == 0) {
            return 0;
        }

        Path tmpDir = null;
        Path input = null;
        try {
            tmpDir = Files.createTempDirectory("wechat-ilink-tts-");
            input = tmpDir.resolve("input." + normalizeExt(inputExt));
            Files.write(input, inputAudio);
            double sec = parseDuration(input);
            int ms = (int) Math.round(sec * 1000);
            return Math.max(ms, 0);
        } catch (Exception e) {
            System.err.println("[AudioConverter] 探测时长失败：" + e.getMessage());
            return 0;
        } finally {
            deleteQuietly(input);
            deleteQuietly(tmpDir);
        }
    }

    /**
     * 把任意 ffmpeg 支持的音频字节流转成微信 SILK 格式。
     *
     * @param inputAudio 原始音频字节（如 MP3/WAV）
     * @param inputExt   原始音频扩展名，用于生成临时文件（mp3 / wav）
     * @return SILK 编码后的音频及播放信息
     */
    public SilkAudio toSilk(byte[] inputAudio, String inputExt) throws IOException {
        if (!available()) {
            throw new IOException(
                    "未找到 ffmpeg / ffprobe / rust-silk。请检查 ai-bot.properties 中的路径配置：\n" +
                    "  ai.ffmpeg-path、ai.ffprobe-path、ai.rust-silk-path");
        }
        if (inputAudio == null || inputAudio.length == 0) {
            throw new IOException("输入音频为空");
        }

        Path tmpDir = Files.createTempDirectory("wechat-ilink-tts-");
        Path input = tmpDir.resolve("input." + normalizeExt(inputExt));
        Path wav = tmpDir.resolve("intermediate.wav");
        Path output = tmpDir.resolve("output.silk");

        try {
            Files.write(input, inputAudio);

            // 1) 先获取原始音频时长（毫秒）
            int playTimeMs = (int) Math.round(parseDuration(input) * 1000);
            if (playTimeMs <= 0) {
                playTimeMs = 1000; // 保底 1 秒，防止微信不显示时长
            }

            // 2) ffmpeg 转成 24000Hz 单声道 s16 WAV（微信 iLink SILK 的标准采样率，rust-silk 要求的输入格式）
            runCommand(
                    ffmpeg,
                    "-y",
                    "-i", input.toString(),
                    "-ar", String.valueOf(SILK_SAMPLE_RATE),
                    "-ac", "1",
                    "-sample_fmt", "s16",
                    wav.toString());

            // 3) rust-silk 编码成 SILK。
            // 微信 iLink 协议要求的 SILK 文件头是 0x02 + "#!SILK_V3" + 帧数据，
            // 对应 rust-silk 的 --tencent 模式（仅在文件头多写 1 字节 0x02）。
            // 如果不传 --tencent，输出的 SILK 是 "#!SILK_V3" 开头的"标准 SILK"格式，
            // 服务端能"接收"消息（ret=0）但 SILK 解码器拒识，导致对端实际收不到。
            runCommand(
                    rustSilk,
                    "encode",
                    "-i", wav.toString(),
                    "-o", output.toString(),
                    "--sample-rate", String.valueOf(SILK_SAMPLE_RATE),
                    "--tencent",
                    "--quiet");

            byte[] silkBytes = Files.readAllBytes(output);
            if (silkBytes.length == 0) {
                throw new IOException("rust-silk 输出的 SILK 文件为空");
            }
            return new SilkAudio(silkBytes, playTimeMs, SILK_SAMPLE_RATE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("音频转码被中断", e);
        } finally {
            deleteQuietly(input);
            deleteQuietly(wav);
            deleteQuietly(output);
            deleteQuietly(tmpDir);
        }
    }

    private double parseDuration(Path input) throws IOException, InterruptedException {
        String out = runCommandAndCapture(
                ffprobe,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString());
        if (out == null || out.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drainToConsole(p.getInputStream());
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(cmd[0] + " 执行超时");
        }
        if (p.exitValue() != 0) {
            throw new IOException(cmd[0] + " 执行失败，退出码 " + p.exitValue());
        }
    }

    private String runCommandAndCapture(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try (InputStream is = p.getInputStream()) {
            while ((n = is.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(cmd[0] + " 执行超时");
        }
        return baos.toString("UTF-8");
    }

    private void drainToConsole(InputStream is) {
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[audio-tool] " + line);
                }
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static String findExecutable(String explicitPath, String name) {
        if (explicitPath != null && !explicitPath.trim().isEmpty()) {
            File f = new File(explicitPath.trim());
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
            // Windows 可能没写 .exe
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                File withExe = new File(explicitPath.trim() + ".exe");
                if (withExe.isFile() && withExe.canExecute()) {
                    return withExe.getAbsolutePath();
                }
            }
        }

        // 1) PATH 环境变量查找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] dirs = pathEnv.split(File.pathSeparator);
            for (String dir : dirs) {
                String found = tryExecutable(dir, name);
                if (found != null) {
                    return found;
                }
            }
        }

        // 2) 项目 tools 目录（自动下载的 ffmpeg / rust-silk）
        String projectTools = System.getProperty("user.dir") + File.separator + "tools";
        List<String> toolPaths = Arrays.asList(
                projectTools,
                projectTools + File.separator + "ffmpeg-master-latest-win64-gpl" + File.separator + "bin",
                projectTools + File.separator + "node_modules" + File.separator + "rust-silk-windows-x64-msvc" + File.separator + "bin",
                projectTools + File.separator + "node_modules" + File.separator + ".bin"
        );
        for (String dir : toolPaths) {
            String found = tryExecutable(dir, name);
            if (found != null) {
                return found;
            }
        }

        // 3) Windows 常见安装路径
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            List<String> commonPaths = Arrays.asList(
                    "C:/ffmpeg/bin",
                    "C:/Program Files/ffmpeg/bin",
                    "C:/Program Files (x86)/ffmpeg/bin",
                    "D:/ffmpeg/bin",
                    System.getProperty("user.home") + "/ffmpeg/bin"
            );
            for (String dir : commonPaths) {
                String found = tryExecutable(dir, name);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static String tryExecutable(String dir, String name) {
        File base = new File(dir, name);
        if (base.isFile() && base.canExecute()) {
            return base.getAbsolutePath();
        }
        File win = new File(dir, name + ".exe");
        if (win.isFile() && win.canExecute()) {
            return win.getAbsolutePath();
        }
        return null;
    }

    private static String normalizeExt(String ext) {
        if (ext == null) {
            return "mp3";
        }
        ext = ext.trim();
        if (ext.isEmpty()) {
            return "mp3";
        }
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return ext.isEmpty() ? "mp3" : ext;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
