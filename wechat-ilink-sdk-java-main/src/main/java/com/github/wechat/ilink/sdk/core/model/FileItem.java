package com.github.wechat.ilink.sdk.core.model;

public class FileItem {
  private CDNMedia media;
  private String file_name;
  private String md5;
  private String len;

  public CDNMedia getMedia() {
    return media;
  }

  public void setMedia(CDNMedia v) {
    media = v;
  }

  public String getFile_name() {
    return file_name;
  }

  public void setFile_name(String v) {
    file_name = v;
  }

  public String getMd5() {
    return md5;
  }

  public void setMd5(String v) {
    md5 = v;
  }

  public String getLen() {
    return len;
  }

  public void setLen(String v) {
    len = v;
  }
}
