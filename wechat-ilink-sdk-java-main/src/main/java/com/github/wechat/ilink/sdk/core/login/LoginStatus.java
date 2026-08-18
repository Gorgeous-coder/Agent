package com.github.wechat.ilink.sdk.core.login;

public class LoginStatus {
  public enum Status {
    NOT_LOGIN,
    WAITING,
    SCANNED,
    LOGGED_IN,
    EXPIRED,
    ERROR
  }

  private volatile Status status = Status.NOT_LOGIN;
  private volatile String errorMessage;

  public synchronized void toWaiting() {
    status = Status.WAITING;
  }

  public synchronized void toScanned() {
    status = Status.SCANNED;
  }

  public synchronized void toLoggedIn() {
    status = Status.LOGGED_IN;
  }

  public synchronized void toExpired() {
    status = Status.EXPIRED;
  }

  public synchronized void toError(String msg) {
    status = Status.ERROR;
    errorMessage = msg;
  }

  public synchronized void reset() {
    status = Status.NOT_LOGIN;
    errorMessage = null;
  }

  public Status getStatus() {
    return status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public boolean isLoggedIn() {
    return status == Status.LOGGED_IN;
  }
}
