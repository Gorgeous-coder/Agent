package com.github.wechat.ilink.sdk.core.listener;

import com.github.wechat.ilink.sdk.core.login.LoginContext;

public interface OnLoginListener {
  void onLoginSuccess(LoginContext context);

  void onLoginFailure(Throwable throwable);
}
