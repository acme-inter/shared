package com.acme.shared.service;

import com.acme.shared.exception.GoogleOAuthException;
import com.acme.shared.payload.oauth.GoogleResponseDTO;
import com.acme.shared.payload.oauth.OAuthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public abstract class AbstractGoogleOAuthService {

  @Value("${google.oauth.client-id}")
  private String clientId;

  @Value("${google.oauth.client-secret}")
  private String clientSecrete;

  @Value("${google.oauth.redirect-uri}")
  private String redirectUri;

  private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
  private static final String TOKEN_URL     = "https://oauth2.googleapis.com/token";
  private static final String REVOKE_URL    = "https://oauth2.googleapis.com/revoke";

  private final WebClient webClient;

  protected abstract String scopes();

  public String buildConsentUrl(Long memberId, String returnUrl) {
    String json  = String.format("{\"memberId\":%d,\"returnUrl\":\"%s\"}", memberId, returnUrl);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    return AUTHORIZE_URL
        + "?client_id="    + encode(clientId)
        + "&redirect_uri=" + encode(redirectUri)
        + "&response_type=code"
        + "&scope="        + encode(scopes())
        + "&access_type=offline"
        + "&prompt=consent"
        + "&state="        + state;
  }

  public OAuthState decodeState(String state) {
    if (state == null || state.isBlank()) return OAuthState.empty();
    try {
      String json      = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
      Long   memberId  = extractLong(json);
      String returnUrl = extractString(json);
      return new OAuthState(memberId, returnUrl != null ? returnUrl : "/");
    } catch (Exception e) {
      log.warn("Could not decode OAuth state param: {}", state);
      return OAuthState.empty();
    }
  }

  public Mono<GoogleResponseDTO> exchangeCode(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("code",          code);
    form.add("client_id",     clientId);
    form.add("client_secret", clientSecrete);
    form.add("redirect_uri",  redirectUri);
    form.add("grant_type",    "authorization_code");
    return webClient.post()
        .uri(TOKEN_URL)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData(form))
        .retrieve()
        .onStatus(
            s -> s.is4xxClientError() || s.is5xxServerError(),
            r -> r.bodyToMono(String.class)
                .flatMap(body -> Mono.error(
                    new GoogleOAuthException("Token exchange failed: " + body)))
        )
        .bodyToMono(GoogleResponseDTO.class);
  }


  public Mono<GoogleResponseDTO> refreshAccessToken(String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("refresh_token", refreshToken);
    form.add("client_id",     clientId);
    form.add("client_secret", clientSecrete);
    form.add("grant_type",    "refresh_token");

    return webClient.post()
        .uri(TOKEN_URL)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData(form))
        .retrieve()
        .onStatus(
            s -> s.is4xxClientError() || s.is5xxServerError(),
            r -> r.bodyToMono(String.class)
                .flatMap(body -> Mono.error(
                    new GoogleOAuthException("Token refresh failed: " + body)))
        )
        .bodyToMono(GoogleResponseDTO.class);
  }

  public Mono<Void> revokeToken(String accessToken) {
    return webClient.post()
        .uri(REVOKE_URL + "?token=" + encode(accessToken))
        .retrieve()
        .bodyToMono(Void.class)
        .doOnError(e -> log.warn("Google token revocation failed (best-effort): {}", e.getMessage()))
        .onErrorResume(e -> Mono.empty());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Long extractLong(String json) {
    try {
      String pattern = "\"" + "memberId" + "\":";
      int idx = json.indexOf(pattern);
      if (idx < 0) return null;
      int start = idx + pattern.length();
      int end   = start;
      while (end < json.length()
          && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
      return Long.parseLong(json.substring(start, end).trim());
    } catch (Exception e) { return null; }
  }

  private static String extractString(String json) {
    try {
      String pattern = "\"" + "returnUrl" + "\":\"";
      int idx = json.indexOf(pattern);
      if (idx < 0) return null;
      int start = idx + pattern.length();
      int end   = json.indexOf('"', start);
      return end < 0 ? null : json.substring(start, end);
    } catch (Exception e) { return null; }
  }
}
