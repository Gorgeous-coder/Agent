package com.github.wechat.ilink.sdk.core.model;

public class CDNMedia {
  private String encrypt_query_param;
  private String aes_key;
  private Integer encrypt_type;

  public String getEncrypt_query_param() {
    return encrypt_query_param;
  }

  public void setEncrypt_query_param(String v) {
    encrypt_query_param = v;
  }

  public String getAes_key() {
    return aes_key;
  }

  public void setAes_key(String v) {
    aes_key = v;
  }

  public Integer getEncrypt_type() {
    return encrypt_type;
  }

  public void setEncrypt_type(Integer v) {
    encrypt_type = v;
  }
}
