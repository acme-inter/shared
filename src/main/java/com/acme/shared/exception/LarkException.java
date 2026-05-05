package com.acme.shared.exception;

import lombok.Getter;

@Getter
public class LarkException extends RuntimeException {

  private final int code;

  public LarkException(int code, String message) {
    super("[Lark " + code + "] " + message);
    this.code = code;
  }
}
