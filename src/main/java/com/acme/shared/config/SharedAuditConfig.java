package com.acme.shared.config;

import com.acme.shared.annotation.CreatedDepartment;
import com.acme.shared.annotation.UpdatedDepartment;
import com.acme.shared.payload.MemberPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Mono;

public abstract class SharedAuditConfig {

  /**
   * Extract the member ID from the current authentication.
   * Override in each microservice's {@code AuditConfig}.
   */
  protected Long resolveMemberId(Authentication auth) {
    if (auth.getPrincipal() instanceof MemberPrincipal p) return p.memberId();
    return 0L;
  }

  /**
   * Extract the department ID from the current authentication.
   * Override in each microservice's {@code AuditConfig}.
   */
  protected Long resolveDepartmentId(Authentication auth) {
    if (auth.getPrincipal() instanceof MemberPrincipal p) return p.departmentIdOrDefault();
    return 0L;
  }

  // ─── Spring Data auditor ──────────────────────────────────────────────────

  /**
   * Provides the current member ID to {@code @CreatedBy} / {@code @LastModifiedBy}
   * Spring Data audit fields.
   */
  @Bean
  public ReactiveAuditorAware<Long> auditorAware() {
    return () -> ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(this::resolveMemberId)
        .switchIfEmpty(Mono.just(0L));
  }

  // ─── Department field callback ────────────────────────────────────────────

  /**
   * Before every R2DBC entity insert/update, populates fields annotated with
   * {@link CreatedDepartment} (only when {@code null}) and
   * {@link UpdatedDepartment} (always).
   */
  @Bean
  public BeforeConvertCallback<Object> departmentAuditCallback() {
    return (entity, table) ->
        ReactiveSecurityContextHolder.getContext()
            .mapNotNull(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .map(auth -> {
              Long deptId = resolveDepartmentId(auth);
              setCreatedDepartment(entity, deptId);
              setUpdatedDepartment(entity, deptId);
              return entity;
            })
            .switchIfEmpty(Mono.just(entity));
  }

  private void setCreatedDepartment(Object entity, Long departmentId) {
    ReflectionUtils.doWithFields(
        entity.getClass(),
        field -> {
          ReflectionUtils.makeAccessible(field);
          if (ReflectionUtils.getField(field, entity) == null) {
            ReflectionUtils.setField(field, entity, departmentId);
          }
        },
        field -> field.isAnnotationPresent(CreatedDepartment.class)
    );
  }

  private void setUpdatedDepartment(Object entity, Long departmentId) {
    ReflectionUtils.doWithFields(
        entity.getClass(),
        field -> {
          ReflectionUtils.makeAccessible(field);
          ReflectionUtils.setField(field, entity, departmentId);
        },
        field -> field.isAnnotationPresent(UpdatedDepartment.class)
    );
  }
}
