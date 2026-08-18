package com.github.wechat.ilink.sdk.core.model;

public class BaseInfo {
  private String channel_version;

  public BaseInfo() {}

  public BaseInfo(String v) {
    this.channel_version = v;
  }

  public String getChannel_version() {
    return channel_version;
  }

  public void setChannel_version(String v) {
    channel_version = v;
  }
}
