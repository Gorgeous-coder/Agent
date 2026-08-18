package com.github.wechat.ilink.sdk.core.model;

public class GetConfigRequest {
  private String ilink_user_id;
  private String context_token;
  private BaseInfo base_info;

  public GetConfigRequest(String userId, String contextToken, BaseInfo baseInfo) {
    this.ilink_user_id = userId;
    this.context_token = contextToken;
    this.base_info = baseInfo;
  }

  public String getIlink_user_id() {
    return ilink_user_id;
  }

  public String getContext_token() {
    return context_token;
  }

  public BaseInfo getBase_info() {
    return base_info;
  }
}
