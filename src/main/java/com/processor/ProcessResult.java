package com.processor;

/**
 * 消息处理结果封装
 */
public record ProcessResult(
        Type type,
        String text,
        byte[] data,
        String userId
) {
    public enum Type {
        TEXT,
        IMAGE,
        VOICE
    }

    public static ProcessResult text(String text, String userId) {
        return new ProcessResult(Type.TEXT, text, null, userId);
    }

    public static ProcessResult image(byte[] imageData, String userId) {
        return new ProcessResult(Type.IMAGE, null, imageData, userId);
    }

    public static ProcessResult voice(byte[] audioData, String userId) {
        return new ProcessResult(Type.VOICE, null, audioData, userId);
    }
}