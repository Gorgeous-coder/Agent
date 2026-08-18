package com.github.wechat.ilink.sdk.core.utils;

public final class HexUtils {
  private HexUtils() {}

  public static byte[] decodeHex(String hex) {
    int len = hex.length();
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2)
      out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
    return out;
  }
}
