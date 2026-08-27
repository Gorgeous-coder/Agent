package com.github.wechat.ilink.sdk.core.exception;

public class RequestTimeoutException extends ILinkException {
  public RequestTimeoutException(String m) {
    super(m);
  }

  public RequestTimeoutException(String m, Throwable c) {
    super(m, c);
  }
}
