package com.group26.Agent.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class AudioConverter {

    private static final String FFMPEG_PATH = "D:/ffmpeg-9.0.1-full_build/bin/ffmpeg.exe";

    public static boolean convertToPcm(String inputPath, String outputPath) {
        String[] cmd = {
                FFMPEG_PATH,
                "-i", inputPath,
                "-acodec", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                "-f", "s16le",
                outputPath
        };

        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ PCM 转换成功: " + outputPath);
                return true;
            } else {
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        System.err.println("[FFmpeg Error] " + line);
                    }
                }
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ==================== PCM -> MP3 ====================
    public static boolean convertToMp3(String inputPath, String outputPath) {
        String[] cmd = {
                FFMPEG_PATH,
                "-f", "s16le",
                "-ar", "16000",
                "-ac", "1",
                "-i", inputPath,
                "-acodec", "libmp3lame",
                "-ab", "32k",
                outputPath
        };

        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ MP3 转换成功: " + outputPath);
                return true;
            } else {
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        System.err.println("[FFmpeg Error] " + line);
                    }
                }
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ==================== PCM -> SILK（微信语音格式） ====================
    public static boolean convertToSilk(String inputPath, String outputPath) {
        // SILK 编码需要 ffmpeg 支持，如果没有 libsilk，用下面这个替代方案
        // 先用 PCM -> OGG (libopus)，微信也支持
        String oggPath = outputPath.replace(".silk", ".ogg");

        String[] cmd = {
                FFMPEG_PATH,
                "-f", "s16le",
                "-ar", "16000",
                "-ac", "1",
                "-i", inputPath,
                "-c:a", "libopus",
                "-b:a", "24k",
                "-application", "voip",
                "-f", "ogg",
                oggPath
        };

        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ OGG 转换成功: " + oggPath);
                return true;
            } else {
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        System.err.println("[FFmpeg Error] " + line);
                    }
                }
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ==================== 字节数组直接转 MP3 ====================
    public static byte[] pcmToMp3Bytes(byte[] pcmData) {
        try {
            java.io.File pcmFile = java.io.File.createTempFile("audio_", ".pcm");
            java.nio.file.Files.write(pcmFile.toPath(), pcmData);

            java.io.File mp3File = java.io.File.createTempFile("audio_", ".mp3");

            String[] cmd = {
                    FFMPEG_PATH,
                    "-f", "s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    "-i", pcmFile.getAbsolutePath(),
                    "-acodec", "libmp3lame",
                    "-ab", "32k",
                    mp3File.getAbsolutePath()
            };

            ProcessBuilder builder = new ProcessBuilder(cmd);
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                byte[] mp3Data = java.nio.file.Files.readAllBytes(mp3File.toPath());
                pcmFile.delete();
                mp3File.delete();
                return mp3Data;
            } else {
                pcmFile.delete();
                mp3File.delete();
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}