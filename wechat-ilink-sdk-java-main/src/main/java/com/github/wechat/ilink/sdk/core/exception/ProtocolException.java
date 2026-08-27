package com.github.wechat.ilink.sdk.core.exception;

public class ProtocolException extends ILinkException {
  public ProtocolException(String m) {
    super(m);
  }

  public ProtocolException(String m, Throwable c) {
    super(m, c);
  }
}
