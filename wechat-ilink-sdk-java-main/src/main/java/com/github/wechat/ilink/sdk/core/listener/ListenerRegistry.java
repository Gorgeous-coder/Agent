package com.github.wechat.ilink.sdk.core.listener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ListenerRegistry {
  private final List<OnLoginListener> loginListeners = new CopyOnWriteArrayList<OnLoginListener>();
  private final List<OnDisconnectListener> disconnectListeners =
      new CopyOnWriteArrayList<OnDisconnectListener>();
  private final List<OnHeartbeatListener> heartbeatListeners =
      new CopyOnWriteArrayList<OnHeartbeatListener>();
  private final List<OnMessageListener> messageListeners =
      new CopyOnWriteArrayList<OnMessageListener>();

  public void addOnLoginListener(OnLoginListener l) {
    loginListeners.add(l);
  }

  public void addOnDisconnectListener(OnDisconnectListener l) {
    disconnectListeners.add(l);
  }

  public void addOnHeartbeatListener(OnHeartbeatListener l) {
    heartbeatListeners.add(l);
  }

  public void addOnMessageListener(OnMessageListener l) {
    messageListeners.add(l);
  }

  public List<OnLoginListener> getLoginListeners() {
    return loginListeners;
  }

  public List<OnDisconnectListener> getDisconnectListeners() {
    return disconnectListeners;
  }

  public List<OnHeartbeatListener> getHeartbeatListeners() {
    return heartbeatListeners;
  }

  public List<OnMessageListener> getMessageListeners() {
    return messageListeners;
  }
}
