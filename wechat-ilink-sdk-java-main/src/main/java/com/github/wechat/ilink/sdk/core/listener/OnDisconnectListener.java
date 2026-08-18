package com.github.wechat.ilink.sdk.core.listener;

public interface OnDisconnectListener {
  void onDisconnect(Throwable cause);

  void onReconnectStart(int attempt);

  void onReconnectSuccess();

  void onReconnectFailed(Throwable cause);
}
