package com.github.wechat.ilink.sdk.core.listener;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import java.util.List;

public interface OnMessageListener {
  void onMessages(List<WeixinMessage> messages);
}
