package com.github.wechat.ilink.sdk.core.model;

import java.util.List;

public class GetUpdatesResponse extends ApiResponse {
  private List<WeixinMessage> msgs;
  private String get_updates_buf;

  public List<WeixinMessage> getMsgs() {
    return msgs;
  }

  public void setMsgs(List<WeixinMessage> v) {
    msgs = v;
  }

  public String getGet_updates_buf() {
    return get_updates_buf;
  }

  public void setGet_updates_buf(String v) {
    get_updates_buf = v;
  }
}
