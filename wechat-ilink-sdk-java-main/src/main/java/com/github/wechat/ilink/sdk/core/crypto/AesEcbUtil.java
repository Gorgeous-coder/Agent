package com.github.wechat.ilink.sdk.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class AesEcbUtil {
  private AesEcbUtil() {}

  public static byte[] encryptPkcs7(byte[] plain, byte[] key) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(plain);
    } catch (Exception e) {
      throw new IllegalStateException("AES encrypt failed", e);
    }
  }

  public static byte[] decryptPkcs7(byte[] enc, byte[] key) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(enc);
    } catch (Exception e) {
      throw new IllegalStateException("AES decrypt failed", e);
    }
  }
}
