package com.github.wechat.ilink.sdk.core.model;

public class GetUpdatesRequest {
  private String get_updates_buf;
  private BaseInfo base_info;

  public GetUpdatesRequest(String cursor, BaseInfo baseInfo) {
    this.get_updates_buf = cursor;
    this.base_info = baseInfo;
  }

  public String getGet_updates_buf() {
    return get_updates_buf;
  }

  public BaseInfo getBase_info() {
    return base_info;
  }
}
