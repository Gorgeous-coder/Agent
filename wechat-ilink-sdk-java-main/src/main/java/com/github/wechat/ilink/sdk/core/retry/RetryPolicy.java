package com.github.wechat.ilink.sdk.core.retry;

public class RetryPolicy {
  private final int maxAttempts;
  private final BackoffStrategy backoffStrategy;

  public RetryPolicy(int maxAttempts, BackoffStrategy backoffStrategy) {
    this.maxAttempts = maxAttempts;
    this.backoffStrategy = backoffStrategy;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public long nextDelayMillis(int attempt) {
    return backoffStrategy.nextDelayMillis(attempt);
  }
}
