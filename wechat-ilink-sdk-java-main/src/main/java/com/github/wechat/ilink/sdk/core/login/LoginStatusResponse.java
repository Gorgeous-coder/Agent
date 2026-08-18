package com.github.wechat.ilink.sdk.core.login;

public class LoginStatusResponse {
  private final String status;
  private String botToken;
  private String botId;
  private String userId;
  private String baseUrl;

  public LoginStatusResponse(String status) {
    this.status = status;
  }

  public String getStatus() {
    return status;
  }

  public String getBotToken() {
    return botToken;
  }

  public void setBotToken(String v) {
    botToken = v;
  }

  public String getBotId() {
    return botId;
  }

  public void setBotId(String v) {
    botId = v;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String v) {
    userId = v;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String v) {
    baseUrl = v;
  }

  public boolean isConfirmed() {
    return "confirmed".equalsIgnoreCase(status);
  }

  public boolean isScanned() {
    return "scaned".equalsIgnoreCase(status) || "scanned".equalsIgnoreCase(status);
  }

  public boolean isWaiting() {
    return "wait".equalsIgnoreCase(status) || "waiting".equalsIgnoreCase(status);
  }

  public boolean isExpired() {
    return "expired".equalsIgnoreCase(status);
  }
}
