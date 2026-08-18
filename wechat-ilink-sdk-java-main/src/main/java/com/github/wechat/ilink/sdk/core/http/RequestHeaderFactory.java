package com.github.wechat.ilink.sdk.core.http;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.utils.RandomUtils;
import java.util.HashMap;
import java.util.Map;

public final class RequestHeaderFactory {
  private RequestHeaderFactory() {}

  public static Map<String, String> businessHeaders(
      ILinkConfig config, LoginContext loginContext, byte[] utf8Body) {
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("Content-Type", "application/json");
    headers.put("AuthorizationType", "ilink_bot_token");
    headers.put("Authorization", "Bearer " + loginContext.getBotToken());
    headers.put("X-WECHAT-UIN", RandomUtils.randomWechatUin());
    headers.put("Content-Length", String.valueOf(utf8Body == null ? 0 : utf8Body.length));
    if (config.getRouteTag() != null && !config.getRouteTag().trim().isEmpty())
      headers.put("SKRouteTag", config.getRouteTag());
    return headers;
  }
}
