package com.acme.shared.util;

import com.acme.shared.enums.Action;
import com.acme.shared.enums.Modules;
import com.acme.shared.enums.RoleType;
import com.acme.shared.exception.ThrowException;
import com.acme.shared.payload.ApiResponse;
import com.acme.shared.payload.MemberPrincipal;
import com.acme.shared.payload.agent.AgentDTO;
import com.acme.shared.payload.audit.AuditDTO;
import com.acme.shared.payload.event.LogMsgDTO;
import com.acme.shared.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbUtil {

  public final R2dbcEntityTemplate template;
  private final EventService eventService;
  private final MsgUtil msgUtil;

  public <T> Mono<T> getById(Long id, Class<T> clazz) {
    return template.selectOne(
        Query.query(Criteria.where("id").is(id)),
        clazz
    );
  }

  public <T> Mono<T> save(T entity, Class<T> clazz) {
    return template.insert(clazz).using(entity);
  }
  public <T> Mono<T> save(T entity,
                          Class<T> clazz,
                          String description) {
    return template.insert(clazz).using(entity)
        .flatMap(saved -> auditContext()
            .flatMap(audit -> eventService.publishLog(
                LogMsgDTO.from(
                    audit,
                    audit.getModule(),     // <-- set properly
                    Action.CREATE.toString(),
                    description,
                    clazz.getSimpleName(),   // or service name
                    null,
                    extractId(saved),
                    null,
                    null
                )
            ))
            .thenReturn(saved));
  }

  public <T> Mono<T> signin(T entity,
                            Class<T> clazz,
                            Modules module,
                            AgentDTO agentDTO,
                            String description,
                            Long memberId,
                            Long departmentId) {
    return template.insert(clazz).using(entity)
        .flatMap(saved -> eventService.publishLog(
                LogMsgDTO.from(
                    agentDTO,
                    module,     // <-- set properly
                    Action.SIGNIN.toString(),
                    description,
                    "authentication",   // or service name
                    null,
                    extractId(saved),
                    memberId,
                    departmentId
                )
            )
            .thenReturn(saved));
  }

  public <T> Mono<T> update(Long id, Update update, Class<T> clazz) {
    return template.update(clazz)
        .matching(Query.query(Criteria.where("id").is(id)))
        .apply(update)
        .then(getById(id, clazz));
  }
  public <T> Mono<T> update(Long id,
                            Update update,
                            Class<T> clazz,
                            String description) {
    Mono<T> fetch = getById(id, clazz);
    return fetch.flatMap(oldEntity ->
        applyUpdate(id, update, clazz)
            .flatMap(newEntity ->
                auditContext()
                    .flatMap(audit -> eventService.publishLog(
                        LogMsgDTO.from(
                            audit,
                            audit.getModule(),     // <-- set properly
                            Action.UPDATE.toString(),
                            clazz.getSimpleName(),   // or service name
                            description,
                            extractOwnerId(oldEntity),
                            id,
                            toJson(oldEntity),
                            toJson(newEntity)
                        )
                    ))
                    .thenReturn(newEntity)
            )
    );
  }
  public <T> Mono<T> update(Long id, Update update, Class<T> clazz,
                            String notFoundMsgCode,
                            String description,
                            GuardUtil guardUtil,
                            ToLongFunction<T> ownerExtractor,
                            String deniedMessageCode) {
    return getById(id, clazz)
        .switchIfEmpty(Mono.error(new ThrowException(notFoundMsgCode)))
        .flatMap(oldEntity -> {
          Mono<Long> guardCheck = (guardUtil != null)
              ? guardUtil.requireRoleOrOwner(
              () -> ownerExtractor.applyAsLong(oldEntity),
              deniedMessageCode)
              : Mono.just(0L);

          return guardCheck.flatMap(callerId ->
              applyUpdate(id, update, clazz)
                  .flatMap(newEntity ->
                      auditContext()
                          .flatMap(audit -> eventService.publishLog(LogMsgDTO.from(
                              audit, audit.getModule(), Action.UPDATE.toString(),
                              clazz.getSimpleName(), description,
                              extractOwnerId(oldEntity), id,
                              toJson(oldEntity), toJson(newEntity))))
                          .thenReturn(newEntity)));
        });
  }

  public <T> Mono<Void> delete(Long id, Class<T> clazz) {
    return template.delete(clazz)
        .matching(Query.query(Criteria.where("id").is(id)))
        .all()
        .then();
  }

  public Mono<Void> deleteByQuery(Query query, Class<?> clazz) {
    return template.delete(query, clazz).then();
  }

  public <T> Mono<ApiResponse<Void>> deleteByIds(
      List<Long> ids,
      String emptyIdMessage,
      String entityName,
      Class<T> clazz,
      Function<T, ?> nameExtractor,
      BiFunction<List<String>, AuditDTO, Mono<Void>> postDeleteTask,
      boolean isLog,
      GuardUtil guardUtil,
      ToLongFunction<T> ownerExtractor,
      String deniedMessageKey) {

    if (ids == null || ids.isEmpty()) {
      return Mono.error(new ThrowException(emptyIdMessage));
    }
    return auditContext()
        .flatMap(audit ->
            getByIds(ids, clazz)
                .flatMap(existingEntities -> {
                  if (existingEntities.size() < ids.size()) {
                    return msgUtil.get("delete.failed.some.not.found", entityName)
                        .map(ApiResponse::error);
                  }
                  Mono<Long> guardCheck = (guardUtil != null && ownerExtractor != null)
                      ? guardUtil.requireRoleOrOwnerAll(existingEntities, ownerExtractor, deniedMessageKey)
                      : Mono.just(0L);

                  return guardCheck.flatMap(callerId -> {
                    List<String> names = nameExtractor != null
                        ? existingEntities.stream()
                        .map(e -> Optional.ofNullable(nameExtractor.apply(e))
                            .map(Object::toString).orElse(""))
                        .toList()
                        : new ArrayList<>();

                    Update update = Update.update("is_deleted", true)
                        .set("deleted_at", Instant.now())
                        .set("deleted_by", audit.getMemberId());
                    Query query = Query.query(Criteria.where("id").in(ids));

                    return template.update(clazz)
                        .matching(query)
                        .apply(update)
                        .then(Mono.defer(() -> postDeleteTask.apply(names, audit)))
                        .then(Mono.defer(() -> {
                          if (!isLog) return Mono.empty();
                          String desc = "Soft-deleted: " + String.join(", ", names);
                          return eventService.publishLog(LogMsgDTO.from(
                              audit, audit.getModule(), Action.DELETE.toString(),
                              clazz.getSimpleName(), desc,
                              null, null, null, null));
                        }))
                        .then(msgUtil.get("delete.success.selected", entityName)
                            .map(ApiResponse::<Void>success));
                  });
                })
        )
        .onErrorResume(ThrowException.class, e ->
            Mono.just(ApiResponse.error(e.getMessage()))
        )
        .onErrorResume(e ->
            msgUtil.get("delete.failed.selected", entityName)
                .map(msg -> ApiResponse.error(msg, e.getMessage()))
        );
  }

  public <T> Mono<ApiResponse<Void>> deletePermanentByIds(
      List<Long> ids,
      String emptyIdMessage,
      String entityName,
      Class<T> clazz,
      Function<T, ?> nameExtractor,
      BiFunction<List<String>, AuditDTO, Mono<Void>> postDeleteTask,
      boolean isLog,
      GuardUtil guardUtil,
      ToLongFunction<T> ownerExtractor,
      String deniedMessageKey) {

    if (ids == null || ids.isEmpty()) {
      return Mono.error(new ThrowException(emptyIdMessage));
    }
    return auditContext()
        .flatMap(audit ->
            getByIds(ids, clazz)
                .flatMap(existingEntities -> {
                  if (existingEntities.size() < ids.size()) {
                    return msgUtil.get("delete.failed.some.not.found", entityName)
                        .map(ApiResponse::error);
                  }
                  Mono<Long> guardCheck = (guardUtil != null && ownerExtractor != null)
                      ? guardUtil.requireRoleOrOwnerAll(existingEntities, ownerExtractor, deniedMessageKey)
                      : Mono.just(0L);

                  return guardCheck.flatMap(callerId -> {
                    List<String> names = nameExtractor != null
                        ? existingEntities.stream()
                        .map(e -> Optional.ofNullable(nameExtractor.apply(e))
                            .map(Object::toString).orElse(""))
                        .toList()
                        : new ArrayList<>();

                    Query query = Query.query(Criteria.where("id").in(ids));
                    return deleteByQuery(query, clazz)
                        .then(Mono.defer(() -> postDeleteTask.apply(names, audit)))
                        .then(Mono.defer(() -> {
                          if (!isLog) return Mono.empty();
                          String desc = "Permanently deleted: " + String.join(", ", names);
                          return eventService.publishLog(LogMsgDTO.from(
                              audit, audit.getModule(), Action.DELETE.toString(),
                              clazz.getSimpleName(), desc,
                              null, null, null, null));
                        }))
                        .then(msgUtil.get("delete.success.selected", entityName)
                            .map(ApiResponse::<Void>success));
                  });
                })
        )
        .onErrorResume(ThrowException.class, e ->
            Mono.just(ApiResponse.error(e.getMessage()))
        )
        .onErrorResume(e ->
            msgUtil.get("delete.failed.selected", entityName)
                .map(msg -> ApiResponse.error(msg, e.getMessage()))
        );
  }

  public <T> Mono<List<T>> getByIds(List<Long> ids, Class<T> clazz) {
    if (ids == null || ids.isEmpty()) {
      return Mono.just(Collections.emptyList());
    }
    Query query = Query.query(Criteria.where("id").in(ids));
    return template.select(query, clazz).collectList();
  }

  public <T> Mono<T> getByQuery(Query query, Class<T> clazz) {
    return template.selectOne(query, clazz);
  }

  public <T> Flux<T> getAllByQuery(Query query, Class<T> clazz) {
    return template.select(query, clazz);
  }

  public <T> Mono<Boolean> existById(Long id, Class<T> clazz) {
    return template
        .select(clazz)
        .matching(Query.query(Criteria.where("id").is(id)))
        .exists();
  }

  public Mono<Long> getMemberId() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .mapNotNull(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal memberPrincipal) {
            return memberPrincipal.memberId();
          }
          return null;
        })
        .defaultIfEmpty(0L);
  }

  // ─── HELPER ──────────────────────────────────────────────────────────────────
  private <T> Mono<T> applyUpdate(Long id, Update update, Class<T> clazz) {
    return template.update(clazz)
        .matching(Query.query(Criteria.where("id").is(id)))
        .apply(update)
        .then(getById(id, clazz));
  }

  public Mono<AuditDTO> auditContext() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal(
              Boolean isApi,
              Long memberId,
              Long departmentId,
              Long sessionId,
              String roleType,
              String module,
              String lang,
              String ip,
              String browser,
              String deviceType
          )) {
            return AuditDTO.builder()
                .isApi(isApi)
                .memberId(memberId)
                .departmentId(departmentId)
                .sessionId(sessionId)
                .lang(lang)
                .ip(ip)
                .browser(browser)
                .deviceType(deviceType)
                .module(Modules.valueOf(module))
                .roleType(RoleType.valueOf(roleType))
                .build();
          }

          return AuditDTO.builder().build();
        })
        .defaultIfEmpty(AuditDTO.builder().build());
  }

  private <T> Long extractId(T entity) {
    try {
      var method = entity.getClass().getMethod("getId");
      Object val = method.invoke(entity);
      return val instanceof Long l ? l : null;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to extract id", e);
    }
  }

  private Long extractOwnerId(Object entity) {
    if (entity == null) return null;
    try {
      Long fromMethod = extractOwnerIdByMethod(entity);
      if (fromMethod != null) return fromMethod;
      return extractOwnerIdByField(entity);
    } catch (Exception e) {
      return null;
    }
  }

  private Long extractOwnerIdByMethod(Object entity) {
    try {
      var method = entity.getClass().getMethod("getCreatedBy");
      Object val = method.invoke(entity);
      return val instanceof Long l ? l : null;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
      return null;
    }
  }

  private Long extractOwnerIdByField(Object entity) {
    try {
      var field = entity.getClass().getDeclaredField("createdBy");
      Object val = field.get(entity);
      return val instanceof Long l ? l : null;
    } catch (Exception e) {
      return null;
    }
  }

  public String toJson(Object obj) {
    if (obj == null) return null;
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      log.warn("Failed to serialize entity to JSON: {}", e.getMessage());
      return obj.toString();
    }
  }

  public <T> T parseJson(String json, Class<T> clazz) {
    if (json == null) return null;
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      return null;
    }
  }

  public <T> List<T> parseJsonList(String json, Class<T> type) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(json, objectMapper.getTypeFactory()
          .constructCollectionType(List.class, type));
    } catch (Exception e) {
      throw new ThrowException("JSON list parse failed for " + type.getSimpleName());
    }
  }
}