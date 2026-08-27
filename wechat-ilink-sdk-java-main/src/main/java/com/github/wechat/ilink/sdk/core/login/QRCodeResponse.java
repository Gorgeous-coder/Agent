package com.github.wechat.ilink.sdk.core.login;

public class QRCodeResponse {
  private final String qrcode;
  private final String qrcodeImgContent;

  public QRCodeResponse(String qrcode, String qrcodeImgContent) {
    this.qrcode = qrcode;
    this.qrcodeImgContent = qrcodeImgContent;
  }

  public String getQrcode() {
    return qrcode;
  }

  public String getQrcodeImgContent() {
    return qrcodeImgContent;
  }
}
