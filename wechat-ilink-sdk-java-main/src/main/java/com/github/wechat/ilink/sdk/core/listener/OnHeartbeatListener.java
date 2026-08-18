package com.github.wechat.ilink.sdk.core.listener;

public interface OnHeartbeatListener {
  void onHeartbeatSuccess();

  void onHeartbeatFailure(Throwable cause);
}
