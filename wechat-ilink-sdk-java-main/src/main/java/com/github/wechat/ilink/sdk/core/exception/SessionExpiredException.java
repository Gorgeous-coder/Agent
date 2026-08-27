package com.github.wechat.ilink.sdk.core.exception;

public class SessionExpiredException extends ILinkException {
  public SessionExpiredException(String m) {
    super(m);
  }

  public SessionExpiredException(String m, Throwable c) {
    super(m, c);
  }
}
