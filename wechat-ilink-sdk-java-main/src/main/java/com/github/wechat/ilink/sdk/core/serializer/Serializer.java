package com.github.wechat.ilink.sdk.core.serializer;

public interface Serializer {
  String serialize(Object obj);

  <T> T deserialize(String text, Class<T> clazz);
}
