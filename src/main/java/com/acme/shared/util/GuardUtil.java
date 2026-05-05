package com.acme.shared.util;

import com.acme.shared.enums.RoleType;
import com.acme.shared.exception.ThrowException;
import com.acme.shared.payload.audit.AuditDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

@Component
@RequiredArgsConstructor
public class GuardUtil {

  private final DbUtil dbUtil;

  public static final Set<RoleType> PRIVILEGED_ROLES = Set.of(
      RoleType.DEV,
      RoleType.ADMINISTRATOR,
      RoleType.BUMANAGER
  );

  public Mono<Long> memberId() {
    return dbUtil.auditContext().mapNotNull(AuditDTO::getMemberId);
  }

  public Mono<RoleType> roleType() {
    return dbUtil.auditContext()
        .mapNotNull(audit -> {
          if (audit.getRoleType() == null) return null;
          try { return audit.getRoleType(); }
          catch (IllegalArgumentException ignored) { return null; }
        });
  }

  public Mono<AuditDTO> auditContext() {
    return dbUtil.auditContext();
  }

  public Mono<Long> requireRoleOrOwner(LongSupplier ownerIdSupplier,
                                       String deniedMessageKey) {
    return requireRoleOrOwner(null, ownerIdSupplier, deniedMessageKey);
  }

  public Mono<Long> requireRoleOrOwner(Set<RoleType> extraRoles,
                                       LongSupplier ownerIdSupplier,
                                       String deniedMessageKey) {
    return Mono.zip(
            memberId().defaultIfEmpty(0L),
            roleType().defaultIfEmpty(RoleType.MEMBER)
        )
        .flatMap(tuple -> {
          long     callerId    = tuple.getT1();
          RoleType role        = tuple.getT2();

          boolean isPrivileged = PRIVILEGED_ROLES.contains(role)
              || (extraRoles != null && extraRoles.contains(role));

          if (isPrivileged) return Mono.just(callerId);

          long ownerId = ownerIdSupplier.getAsLong();
          if (ownerId != 0L && ownerId == callerId) return Mono.just(callerId);

          return Mono.error(new ThrowException(deniedMessageKey));
        });
  }

  public Mono<Long> requireRole(Set<RoleType> requiredRoles, String deniedMessageKey) {
    return Mono.zip(
            memberId().defaultIfEmpty(0L),
            roleType().defaultIfEmpty(RoleType.MEMBER)
        )
        .flatMap(tuple -> {
          Long     callerId = tuple.getT1();
          RoleType role     = tuple.getT2();
          boolean  allowed  = PRIVILEGED_ROLES.contains(role)
              || (requiredRoles != null && requiredRoles.contains(role));
          return allowed
              ? Mono.just(callerId)
              : Mono.error(new ThrowException(deniedMessageKey));
        });
  }

  public <T> Mono<Long> requireRoleOrOwnerAll(Iterable<T> entities,
                                              ToLongFunction<T> ownerExtractor,
                                              String deniedMessageKey) {
    return Mono.zip(
            memberId().defaultIfEmpty(0L),
            roleType().defaultIfEmpty(RoleType.MEMBER)
        )
        .flatMap(tuple -> {
          long callerId     = tuple.getT1();
          RoleType role         = tuple.getT2();
          boolean  isPrivileged = PRIVILEGED_ROLES.contains(role);

          if (isPrivileged) return Mono.just(callerId);

          for (T entity : entities) {
            long ownerId = ownerExtractor.applyAsLong(entity);
            if (ownerId == 0L || ownerId != callerId) {
              return Mono.error(new ThrowException(deniedMessageKey));
            }
          }
          return Mono.just(callerId);
        });
  }
}