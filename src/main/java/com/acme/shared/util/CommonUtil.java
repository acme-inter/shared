package com.acme.shared.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CommonUtil {

  private final MsgUtil msgUtil;

  private static final String REFRESH_TOKEN_COOKIE_KEY = "refresh";
  private static final String ACCESS_TOKEN_COOKIE_KEY = "authorize";
  private static final String BEARER_PREFIX = "Bearer ";

  public Mono<String> extractAccessToken(ServerHttpRequest request) {
    return Mono.defer(() -> {
      String token = Optional.ofNullable(request.getCookies().getFirst(ACCESS_TOKEN_COOKIE_KEY))
          .map(HttpCookie::getValue)
          .filter(v -> !v.isBlank())
          .orElse(null);
      if (token == null) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
          token = authHeader.substring(BEARER_PREFIX.length()).trim();
        }
      }
      return Mono.justOrEmpty(token);
    });
  }

  public Mono<String> extractRefreshToken(ServerHttpRequest request) {
    return Mono.fromCallable(() -> {
      MultiValueMap<String, HttpCookie> cookies = request.getCookies();
      HttpCookie cookie = cookies.getFirst(REFRESH_TOKEN_COOKIE_KEY);
      return cookie != null ? cookie.getValue() : null;
    });
  }

  public Mono<Void> sendErrorResponse(ServerWebExchange exchange, String code, HttpStatus status) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return msgUtil.get(code)
        .defaultIfEmpty(code)
        .flatMap(message -> {
          Map<String, Object> errorResponse = Map.of(
              "success", false,
              "message", message,
              "timestamp", Instant.now().toString()
          );
          try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] bytes = mapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
          } catch (Exception e) {
            log.error("Failed to write JSON error response: {}", e.getMessage());
            return response.setComplete();
          }
        });
  }
}
