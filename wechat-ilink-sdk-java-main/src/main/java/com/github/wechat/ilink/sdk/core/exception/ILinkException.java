package com.github.wechat.ilink.sdk.core.exception;

public class ILinkException extends RuntimeException {
  public ILinkException(String m) {
    super(m);
  }

  public ILinkException(String m, Throwable c) {
    super(m, c);
  }
}
