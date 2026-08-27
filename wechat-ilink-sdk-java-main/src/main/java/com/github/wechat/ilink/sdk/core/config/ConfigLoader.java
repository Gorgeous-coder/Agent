package com.github.wechat.ilink.sdk.core.config;

import java.io.InputStream;
import java.util.Properties;

public final class ConfigLoader {
  private ConfigLoader() {}

  public static ILinkConfig loadDefault() {
    Properties p = new Properties();
    try (InputStream in =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("ilink-sdk.properties")) {
      if (in != null) p.load(in);
    } catch (Exception ignore) {
    }

    ILinkConfig.Builder b = ILinkConfig.builder();
    setLong(p, "ilink.connectTimeoutMs", b::connectTimeoutMs);
    setLong(p, "ilink.readTimeoutMs", b::readTimeoutMs);
    setLong(p, "ilink.writeTimeoutMs", b::writeTimeoutMs);
    setInt(p, "ilink.httpMaxRetries", b::httpMaxRetries);
    setLong(p, "ilink.retryBaseDelayMs", b::retryBaseDelayMs);
    setLong(p, "ilink.retryMaxDelayMs", b::retryMaxDelayMs);
    setBoolean(p, "ilink.retryJitterEnabled", b::retryJitterEnabled);
    setLong(p, "ilink.loginTimeoutMs", b::loginTimeoutMs);
    setBoolean(p, "ilink.heartbeatEnabled", b::heartbeatEnabled);
    setLong(p, "ilink.heartbeatIntervalMs", b::heartbeatIntervalMs);
    setInt(p, "ilink.reconnectMaxAttempts", b::reconnectMaxAttempts);
    setLong(p, "ilink.reconnectBaseDelayMs", b::reconnectBaseDelayMs);
    setLong(p, "ilink.reconnectMaxDelayMs", b::reconnectMaxDelayMs);
    setInt(p, "ilink.ioCoreThreads", b::ioCoreThreads);
    setInt(p, "ilink.ioMaxThreads", b::ioMaxThreads);
    setInt(p, "ilink.schedulerThreads", b::schedulerThreads);
    setInt(p, "ilink.queueCapacity", b::queueCapacity);
    setString(p, "ilink.channelVersion", b::channelVersion);
    setString(p, "ilink.routeTag", b::routeTag);
    setBoolean(p, "ilink.autoReconnectEnabled", b::autoReconnectEnabled);
    return b.build();
  }

  private static void setLong(Properties p, String k, java.util.function.LongConsumer c) {
    String v = resolve(p, k);
    if (v != null && !v.isEmpty()) c.accept(Long.parseLong(v.trim()));
  }

  private static void setInt(Properties p, String k, java.util.function.IntConsumer c) {
    String v = resolve(p, k);
    if (v != null && !v.isEmpty()) c.accept(Integer.parseInt(v.trim()));
  }

  private static void setBoolean(Properties p, String k, java.util.function.Consumer<Boolean> c) {
    String v = resolve(p, k);
    if (v != null && !v.isEmpty()) c.accept(Boolean.parseBoolean(v.trim()));
  }

  private static void setString(Properties p, String k, java.util.function.Consumer<String> c) {
    String v = resolve(p, k);
    if (v != null && !v.isEmpty()) c.accept(v.trim());
  }

  private static String resolve(Properties p, String key) {
    String sys = System.getProperty(key);
    if (sys != null && !sys.trim().isEmpty()) return sys.trim();
    String env = System.getenv(key.replace('.', '_').toUpperCase());
    if (env != null && !env.trim().isEmpty()) return env.trim();
    return p.getProperty(key);
  }
}
