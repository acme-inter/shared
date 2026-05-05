package com.acme.shared.util;

import com.acme.shared.payload.agent.AgentDTO;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class UserAgentUtil {

  private static final String UNKNOWN = "Unknown";

  private static final Pattern SUSPICIOUS_UA_PATTERN = Pattern.compile(
      "(?i)" +
          "python-requests|python-httpx|aiohttp|" +
          "axios|node-fetch|node\\.js|got/|" +
          "curl/|wget/|libcurl/|" +
          "go-http-client|java-http-client|okhttp|" +
          "scrapy|" +
          "playwright|puppeteer|" +
          "selenium|webdriver|phantomjs|" +
          "headlesschrome|" +
          "^$"
  );

  private static final Set<String> BOT_DEVICE_CLASSES = Set.of(
      "Robot", "Mobile Robot"
  );

  private static final UserAgentAnalyzer ANALYZER = UserAgentAnalyzer
      .newBuilder()
      .withCache(5_000)
      .hideMatcherLoadStats()
      .build();

  private UserAgentUtil() {}

  // Primary method — extracts both IP and UA from the live request
  public static Mono<AgentDTO> parse(ServerHttpRequest request) {
    return parse(request, ANALYZER);
  }

  // Overload with injectable analyzer (e.g. for testing)
  public static Mono<AgentDTO> parse(ServerHttpRequest request, UserAgentAnalyzer analyzer) {
    Objects.requireNonNull(request,  "request must not be null");
    Objects.requireNonNull(analyzer, "analyzer must not be null");
    return Mono.fromCallable(() -> {
      String ip    = extractClientIp(request);
      String rawUa = extractUserAgent(request);
      return buildDto(ip, rawUa, analyzer.parse(rawUa));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public static String extractClientIp(ServerHttpRequest request) {
    HttpHeaders headers = request.getHeaders();

    String xff = headers.getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();

    String xri = headers.getFirst("X-Real-IP");
    if (xri != null && !xri.isBlank()) return xri.trim();

    String cf = headers.getFirst("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();

    return extractRemoteAddress(request);
  }

  private static String extractUserAgent(ServerHttpRequest request) {
    String ua = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
    return ua != null ? ua : "";
  }

  private static String extractRemoteAddress(ServerHttpRequest request) {
    InetSocketAddress remote = request.getRemoteAddress();
    if (remote == null) return null;                                   // ← was "unknown", now null
    return remote.getAddress() != null
        ? remote.getAddress().getHostAddress()
        : remote.getHostString();
  }

  private static AgentDTO buildDto(String ip, String rawUa, UserAgent ua) {
    String deviceClass   = field(ua, UserAgent.DEVICE_CLASS);
    boolean isBot        = BOT_DEVICE_CLASSES.contains(deviceClass);
    boolean isSuspicious = SUSPICIOUS_UA_PATTERN.matcher(rawUa).find();

    AgentDTO dto = new AgentDTO();
    dto.setIp         (ip);
    dto.setBrowser    (field(ua, UserAgent.AGENT_NAME));
    dto.setOs         (field(ua, UserAgent.OPERATING_SYSTEM_NAME));
    dto.setDeviceType (deviceClass);
    dto.setDeviceBrand(field(ua, UserAgent.DEVICE_BRAND));
    dto.setBot        (isBot);
    dto.setSuspicious (isSuspicious);
    return dto;
  }

  private static String field(UserAgent ua, String fieldName) {
    String value = ua.getValue(fieldName);
    if (value == null || value.isBlank()
        || UNKNOWN.equalsIgnoreCase(value)
        || "??".equals(value)) {
      return null;
    }
    return value;
  }
}
