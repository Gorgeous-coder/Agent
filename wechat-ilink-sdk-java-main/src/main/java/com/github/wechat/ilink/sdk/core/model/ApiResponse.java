package com.github.wechat.ilink.sdk.core.model;

public class ApiResponse {
  private int ret;
  private Integer errcode;
  private String errmsg;

  public int getRet() {
    return ret;
  }

  public void setRet(int v) {
    ret = v;
  }

  public Integer getErrcode() {
    return errcode;
  }

  public void setErrcode(Integer v) {
    errcode = v;
  }

  public String getErrmsg() {
    return errmsg;
  }

  public void setErrmsg(String v) {
    errmsg = v;
  }
}
