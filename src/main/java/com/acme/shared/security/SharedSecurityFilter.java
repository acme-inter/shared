package com.acme.shared.security;

import com.acme.shared.enums.Modules;
import com.acme.shared.enums.RoleType;
import com.acme.shared.payload.MemberPrincipal;
import com.acme.shared.payload.agent.AgentDTO;
import com.acme.shared.payload.role.PermissionElementDTO;
import com.acme.shared.payload.session.SessionDTO;
import com.acme.shared.util.CommonUtil;
import com.acme.shared.util.Encryption;
import com.acme.shared.util.JwtUtil;
import com.acme.shared.util.UserAgentUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public abstract class SharedSecurityFilter implements WebFilter {

  private final CommonUtil commonUtil;
  private final Encryption encryption;
  private final JwtUtil jwtUtil;

  protected abstract Set<String> excludedPaths();

  public static final String ATTR_AGENT = "agentDTO";

  protected abstract Mono<Void> authorizeSessionById(Long sid,
                                                     String token,
                                                     ServerWebExchange exchange,
                                                     WebFilterChain chain,
                                                     String path,
                                                     String lang,
                                                     AgentDTO agent);

  @NonNull
  @Override
  public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getPath().value();

    Locale locale = exchange.getLocaleContext().getLocale();
    if (locale != null) LocaleContextHolder.setLocale(locale);

    if (isExcluded(path)) return chain.filter(exchange);

    String lang = request.getHeaders().getFirst("Accept-Language");

    return UserAgentUtil.parse(request)
        .flatMap(agent -> {

          exchange.getAttributes().put(ATTR_AGENT, agent);

          if (agent.isBot()) {
            log.warn("Bot blocked: ip={}", agent.getIp());
            return commonUtil.sendErrorResponse(
                exchange, "security.bot.blocked", HttpStatus.FORBIDDEN);
          }

          if (agent.isSuspicious()) {
            log.warn("Suspicious client blocked: ip={}", agent.getIp());
            return commonUtil.sendErrorResponse(
                exchange, "security.suspicious.client", HttpStatus.FORBIDDEN);
          }

          return commonUtil.extractAccessToken(request)
              .switchIfEmpty(Mono.defer(() ->
                  commonUtil.sendErrorResponse(
                          exchange, "security.missing.token", HttpStatus.UNAUTHORIZED)
                      .then(Mono.empty())
              ))
              .flatMap(token -> {
                if (token.isBlank()) {
                  return commonUtil.sendErrorResponse(
                      exchange, "security.missing.token", HttpStatus.UNAUTHORIZED);
                }
                return validateToken(token, exchange, chain, path, lang, agent)
                    .onErrorResume(err -> handleError(err, exchange, path));
              });
        });
  }


  private Mono<Void> validateToken(String token, ServerWebExchange exchange,
                                   WebFilterChain chain, String path, String lang, AgentDTO agent) {
    return jwtUtil.isInvalid(token)
        .flatMap(isInvalid -> {
          if (Boolean.TRUE.equals(isInvalid)) {
            log.warn("Invalid or expired token for path: {}", path);
            return commonUtil.sendErrorResponse(exchange, "security.unauthorized", HttpStatus.UNAUTHORIZED);
          }
          return decodeAndValidateSession(token, exchange, chain, path, lang, agent);
        });
  }

  private Mono<Void> decodeAndValidateSession(String token,
                                              ServerWebExchange exchange,
                                              WebFilterChain chain,
                                              String path,
                                              String lang,
                                              AgentDTO agent) {
    return jwtUtil.decode(token)
        .flatMap(jwt -> {
          Boolean external = jwt.getClaim("external").asBoolean();
          if (Boolean.TRUE.equals(external)) {
            return continueAsExternal(exchange, chain, jwt, token, lang, agent);
          }
          Long sid = Long.valueOf(jwt.getSubject());
          return authorizeSessionById(sid, token, exchange, chain, path, lang, agent);
        });
  }

  protected Mono<Void> authenticateAndContinue(SessionDTO session,
                                               String token,
                                               ServerWebExchange exchange,
                                               WebFilterChain chain,
                                               String module,
                                               String lang,
                                               AgentDTO agent) {
    ServerHttpRequest request = exchange.getRequest();

    MemberPrincipal principal = new MemberPrincipal(
        false,
        session.getMemberId(),
        resolveDepartmentId(request, session),
        session.getId(),
        session.getRoleType(),
        module,
        lang,
        agent.getIp(),
        agent.getBrowser(),
        agent.getDeviceType()
    );
    List<GrantedAuthority> authorities =
        AuthorityUtils.createAuthorityList(session.getRoleType());

    return chain.filter(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
            new UsernamePasswordAuthenticationToken(principal, token, authorities)
        ));
  }

  private Mono<Void> continueAsExternal(ServerWebExchange exchange, WebFilterChain chain,
                                        DecodedJWT jwt, String token, String lang, AgentDTO agent) {

    Long memberId = jwt.getClaim("external").asLong();
    Long departmentId = jwt.getClaim("external").asLong();
    MemberPrincipal principal = new MemberPrincipal(
        true,
        memberId,
        departmentId,
        null,
        RoleType.DEV.toString(),
        Modules.API.toString(),
        lang,
        agent.getIp(),
        agent.getBrowser(),
        agent.getDeviceType()
    );
    List<GrantedAuthority> authorities =
        AuthorityUtils.createAuthorityList(String.valueOf(RoleType.DEV));
    return chain.filter(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
            new UsernamePasswordAuthenticationToken(principal, token, authorities)
        ));
  }

  protected Long resolveDepartmentId(ServerHttpRequest request, SessionDTO session) {
    String deptHeader = request.getHeaders().getFirst("X-Department-Id");
    if (deptHeader != null && !deptHeader.isBlank()) {
      try { return Long.parseLong(deptHeader); }
      catch (NumberFormatException ignored) {
        log.warn("Invalid department header value: {}", deptHeader);
      }
    }
    if (session.getDepartments().isEmpty()) return 0L;
    HttpCookie cookie = request.getCookies().getFirst("DKEY");
    if (cookie != null) {
      try { return Long.parseLong(encryption.decodeKey(cookie.getValue())); }
      catch (Exception e) { log.warn("Failed to decode DKEY cookie: {}", e.getMessage()); }
    }
    return session.getDepartments().getFirst().getId();
  }

  protected PermissionElementDTO getPermissionForModule(SessionDTO session, String module) {
    return Optional.ofNullable(session.getPermissions())
        .flatMap(perms -> perms.stream()
            .filter(p -> module.equalsIgnoreCase(p.getModule().name()))
            .findFirst())
        .orElse(null);
  }

  protected boolean isOperationDenied(String path, HttpMethod method,
                                      MultiValueMap<String, String> query,
                                      PermissionElementDTO perm) {
    if (isGetOperation(path, method, query))                          return false;
    if (path.contains("/sync"))                                       return !Boolean.TRUE.equals(perm.getCanSync());
    if (isAddOperation(path, method, query))                          return !Boolean.TRUE.equals(perm.getCanAdd());
    if (isEditOperation(path, method, query))                         return !Boolean.TRUE.equals(perm.getCanEdit());
    if (isDeleteOperation(path, method, query))                       return !Boolean.TRUE.equals(perm.getCanDelete());
    if (path.contains("/import")    || query.containsKey("import"))   return !Boolean.TRUE.equals(perm.getCanImport());
    if (path.contains("/export")    || query.containsKey("export"))   return !Boolean.TRUE.equals(perm.getCanExport());
    if (path.contains("/download")  || query.containsKey("download")) return !Boolean.TRUE.equals(perm.getCanDownload());
    return false;
  }

  protected Mono<Void> sendError(ServerWebExchange exchange) {
    return commonUtil.sendErrorResponse(exchange, "security.error", HttpStatus.BAD_REQUEST);
  }

  private boolean isExcluded(String path) {
    return excludedPaths().stream().anyMatch(path::startsWith);
  }

  private Mono<Void> handleError(Throwable ex, ServerWebExchange exchange, String path) {
    log.error("Security error on path {}: {}", path, ex.getMessage(), ex);
    return sendError(exchange);
  }

  private boolean isGetOperation(String path, HttpMethod method, MultiValueMap<String, String> query) {
    return method == HttpMethod.GET || path.contains("/get") || query.containsKey("dashboard");
  }

  private boolean isAddOperation(String path, HttpMethod method, MultiValueMap<String, String> query) {
    return method == HttpMethod.POST
        || path.contains("/add") || path.contains("/create")
        || query.containsKey("add") || query.containsKey("create");
  }

  private boolean isEditOperation(String path, HttpMethod method, MultiValueMap<String, String> query) {
    return method == HttpMethod.PUT || method == HttpMethod.PATCH
        || path.contains("/edit") || path.contains("/update")
        || query.containsKey("edit") || query.containsKey("update");
  }

  private boolean isDeleteOperation(String path, HttpMethod method, MultiValueMap<String, String> query) {
    return method == HttpMethod.DELETE
        || path.contains("/delete") || path.contains("/remove")
        || query.containsKey("delete") || query.containsKey("remove");
  }
}
