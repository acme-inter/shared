package com.acme.shared.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T>{
  private Boolean success;
  private String message;
  private String error;
  private T data;

  public static <T> ApiResponse<T> success(String message, T data) {
    return ApiResponse.<T>builder()
        .success(true)
        .message(message)
        .data(data)
        .build();
  }

  public static <T> ApiResponse<T> success(String message) {
    return ApiResponse.<T>builder()
        .success(true)
        .message(message)
        .build();
  }

  public static ApiResponse<Void> error(String message) {
    return ApiResponse.<Void>builder()
        .success(false)
        .message(message)
        .build();
  }

  public static <T> ApiResponse<T> error(Boolean isSuccess, String message , T data) {
    return ApiResponse.<T>builder()
        .success(isSuccess)
        .message(message)
        .data(data)
        .build();
  }

  public static <T> ApiResponse<T> error(String message, String errorDetails) {
    return ApiResponse.<T>builder()
        .success(false)
        .message(message)
        .error(errorDetails)
        .build();
  }
}