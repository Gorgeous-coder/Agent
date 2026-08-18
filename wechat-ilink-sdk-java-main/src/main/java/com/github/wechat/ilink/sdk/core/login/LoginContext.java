package com.github.wechat.ilink.sdk.core.login;

public final class LoginContext {
  private final String botToken;
  private final String userId;
  private final String botId;
  private final String baseUrl;

  public LoginContext(String botToken, String userId, String botId, String baseUrl) {
    this.botToken = botToken;
    this.userId = userId;
    this.botId = botId;
    this.baseUrl = baseUrl;
  }

  public String getBotToken() {
    return botToken;
  }

  public String getUserId() {
    return userId;
  }

  public String getBotId() {
    return botId;
  }

  public String getBaseUrl() {
    return baseUrl;
  }
}
