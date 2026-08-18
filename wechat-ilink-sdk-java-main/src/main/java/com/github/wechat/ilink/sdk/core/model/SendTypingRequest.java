package com.github.wechat.ilink.sdk.core.model;

public class SendTypingRequest {
  private String ilink_user_id;
  private String typing_ticket;
  private Integer status;
  private BaseInfo base_info;

  public SendTypingRequest(String userId, String typingTicket, Integer status, BaseInfo baseInfo) {
    this.ilink_user_id = userId;
    this.typing_ticket = typingTicket;
    this.status = status;
    this.base_info = baseInfo;
  }

  public String getIlink_user_id() {
    return ilink_user_id;
  }

  public String getTyping_ticket() {
    return typing_ticket;
  }

  public Integer getStatus() {
    return status;
  }

  public BaseInfo getBase_info() {
    return base_info;
  }
}
