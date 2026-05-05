package com.acme.shared.helper;

import com.acme.shared.payload.ApiResponse;
import com.acme.shared.util.MsgUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ApiResponseUtil {

  private final MsgUtil msgUtil;

  // ─── Success ─────────────────────────────────────────────────────────────

  /** Success response with a data payload. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data) {
    return msgUtil.get(msgCode)
        .map(msg -> ApiResponse.success(msg, data));
  }

  /** Success response with a data payload and message args. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data, Object... messageArgs) {
    return msgUtil.get(msgCode, messageArgs)
        .map(msg -> ApiResponse.success(msg, data));
  }

  /** Success response with no data payload. */
  public <T> Mono<ApiResponse<T>> success(String msgCode) {
    return msgUtil.get(msgCode)
        .map(ApiResponse::success);
  }

  /** Success response with message args and no data payload. */
  public Mono<ApiResponse<Void>> successParam(String msgCode, Object... messageArgs) {
    return msgUtil.get(msgCode, messageArgs)
        .map(ApiResponse::success);
  }

  // ─── Error ───────────────────────────────────────────────────────────────

  /** Error response with a message code only. */
  public Mono<ApiResponse<Void>> error(String msgCode) {
    return msgUtil.get(msgCode)
        .map(ApiResponse::error);
  }

  /**
   * Error response with optional error-detail string.
   * If {@code errorDetails} is non-null it is also passed as a message argument
   * so the message template can embed it.
   */
  public <T> Mono<ApiResponse<T>> error(String msgCode, String errorDetails) {
    return Mono.justOrEmpty(errorDetails)
        .flatMap(details -> msgUtil.get(msgCode, details))
        .switchIfEmpty(msgUtil.get(msgCode))
        .map(msg -> ApiResponse.<T>error(msg, errorDetails));
  }

  /** Error response with message args and no detail string. */
  public Mono<ApiResponse<Void>> errorParam(String msgCode, Object... messageArgs) {
    return msgUtil.get(msgCode, messageArgs)
        .map(ApiResponse::error);
  }

  /** Error response with both message args and a detail string. */
  public Mono<ApiResponse<Void>> error(String msgCode, String errorDetails, Object... messageArgs) {
    return msgUtil.get(msgCode, messageArgs)
        .map(msg -> ApiResponse.error(msg, errorDetails));
  }

  public <T> Mono<ApiResponse<T>> errorThrow(String msgCode, Throwable e) {
    return msgUtil.get(msgCode)
        .map(msg -> ApiResponse.<T>error(msg, e.getMessage()));
  }
}
