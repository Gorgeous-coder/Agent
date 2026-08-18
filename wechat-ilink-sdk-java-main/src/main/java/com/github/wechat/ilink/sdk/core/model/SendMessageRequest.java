package com.github.wechat.ilink.sdk.core.model;

import java.util.List;

public class SendMessageRequest {
  private Msg msg;
  private BaseInfo base_info;

  public SendMessageRequest(Msg msg, BaseInfo baseInfo) {
    this.msg = msg;
    this.base_info = baseInfo;
  }

  public Msg getMsg() {
    return msg;
  }

  public BaseInfo getBase_info() {
    return base_info;
  }

  public static class Msg {
    private String from_user_id = "";
    private String to_user_id;
    private String client_id;
    private Integer message_type = 2;
    private Integer message_state = 2;
    private String context_token;
    private List<MessageItem> item_list;

    public Msg(String toUserId, String clientId, String contextToken, List<MessageItem> itemList) {
      this.to_user_id = toUserId;
      this.client_id = clientId;
      this.context_token = contextToken;
      this.item_list = itemList;
    }

    public String getFrom_user_id() {
      return from_user_id;
    }

    public String getTo_user_id() {
      return to_user_id;
    }

    public String getClient_id() {
      return client_id;
    }

    public Integer getMessage_type() {
      return message_type;
    }

    public Integer getMessage_state() {
      return message_state;
    }

    public String getContext_token() {
      return context_token;
    }

    public List<MessageItem> getItem_list() {
      return item_list;
    }
  }
}
