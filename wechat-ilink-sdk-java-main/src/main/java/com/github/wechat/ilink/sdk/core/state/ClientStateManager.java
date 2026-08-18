package com.github.wechat.ilink.sdk.core.state;

import java.util.concurrent.atomic.AtomicReference;

public class ClientStateManager {
  private final AtomicReference<ConnectionStatus> status =
      new AtomicReference<ConnectionStatus>(ConnectionStatus.NOT_CONNECTED);

  public ConnectionStatus get() {
    return status.get();
  }

  public void set(ConnectionStatus s) {
    status.set(s);
  }

  public boolean isLoggedIn() {
    return status.get() == ConnectionStatus.LOGGED_IN;
  }
}
