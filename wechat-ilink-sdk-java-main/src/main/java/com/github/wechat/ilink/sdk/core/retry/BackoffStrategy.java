package com.github.wechat.ilink.sdk.core.retry;

public interface BackoffStrategy {
  long nextDelayMillis(int attempt);
}
