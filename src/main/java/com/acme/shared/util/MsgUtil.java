package com.acme.shared.util;

import com.acme.shared.payload.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class MsgUtil {

  private final MessageSource messageSource;

  private Locale normalizeLocale(Locale locale) {
    if (locale == null) {
      return Locale.ENGLISH;
    }
    String lang = locale.getLanguage().toLowerCase();
    return new Locale.Builder().setLanguage(lang).build();
  }

  public Mono<Locale> getCurrentLocale() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal memberPrincipal) {
            String lang = memberPrincipal.lang();
            if (lang != null && !lang.isBlank()) {
              return new Locale.Builder().setLanguage(lang.toLowerCase()).build();
            }
          }
          return LocaleContextHolder.getLocale();
        })
        .defaultIfEmpty(LocaleContextHolder.getLocale());
  }


  public Mono<String> get(String code) {
    return getCurrentLocale()
        .map(locale -> {
          Locale normalized = normalizeLocale(locale);
          try {
            return messageSource.getMessage(code, null, normalized);
          } catch (Exception e) {
            return code;
          }
        });
  }

  public Mono<String> get(String code, Object... args) {
    return getCurrentLocale()
        .map(locale -> {
          Locale normalized = normalizeLocale(locale);
          try {
            return messageSource.getMessage(new DefaultMessageSourceResolvable(new String[]{code}, args), normalized);
          } catch (Exception e) {
            return code;
          }
        });
  }
}