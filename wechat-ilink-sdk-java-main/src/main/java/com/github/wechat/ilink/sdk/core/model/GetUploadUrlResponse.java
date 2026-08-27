package com.github.wechat.ilink.sdk.core.model;

public class GetUploadUrlResponse extends ApiResponse {
  private String upload_param;
  private String thumb_upload_param;

  public String getUpload_param() {
    return upload_param;
  }

  public void setUpload_param(String v) {
    upload_param = v;
  }

  public String getThumb_upload_param() {
    return thumb_upload_param;
  }

  public void setThumb_upload_param(String v) {
    thumb_upload_param = v;
  }
}
