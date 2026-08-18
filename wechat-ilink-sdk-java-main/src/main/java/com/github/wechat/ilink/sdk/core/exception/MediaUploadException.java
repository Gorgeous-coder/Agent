package com.github.wechat.ilink.sdk.core.exception;

public class MediaUploadException extends ILinkException {
  public MediaUploadException(String m) {
    super(m);
  }

  public MediaUploadException(String m, Throwable c) {
    super(m, c);
  }
}
