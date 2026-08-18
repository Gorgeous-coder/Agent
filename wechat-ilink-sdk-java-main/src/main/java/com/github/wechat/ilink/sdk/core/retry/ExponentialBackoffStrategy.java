package com.github.wechat.ilink.sdk.core.retry;

import java.util.concurrent.ThreadLocalRandom;

public class ExponentialBackoffStrategy implements BackoffStrategy {
  private final long baseDelayMs;
  private final long maxDelayMs;
  private final boolean jitterEnabled;

  public ExponentialBackoffStrategy(long baseDelayMs, long maxDelayMs, boolean jitterEnabled) {
    this.baseDelayMs = baseDelayMs;
    this.maxDelayMs = maxDelayMs;
    this.jitterEnabled = jitterEnabled;
  }

  public long nextDelayMillis(int attempt) {
    long delay = Math.min(maxDelayMs, baseDelayMs * (1L << Math.max(0, attempt - 1)));
    if (jitterEnabled) delay += ThreadLocalRandom.current().nextLong(0L, Math.max(1L, delay / 4L));
    return delay;
  }
}
