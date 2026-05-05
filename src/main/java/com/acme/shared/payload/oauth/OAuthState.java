package com.acme.shared.payload.oauth;

public record OAuthState(Long memberId, String returnUrl) {
  public static OAuthState empty() {
    return new OAuthState(null, "/");
  }
}
