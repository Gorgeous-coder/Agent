package com.github.wechat.ilink.sdk.core.utils;

import java.security.MessageDigest;

public final class HashUtils {
  private HashUtils() {}

  public static String md5Hex(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] out = md.digest(data);
      StringBuilder sb = new StringBuilder();
      for (byte b : out) sb.append(String.format("%02x", b & 0xff));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("md5 failed", e);
    }
  }
}
