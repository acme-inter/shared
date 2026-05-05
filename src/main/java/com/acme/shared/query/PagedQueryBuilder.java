package com.acme.shared.query;

import com.acme.shared.enums.Modules;
import com.acme.shared.enums.RoleType;
import com.acme.shared.payload.ListResponse;
import com.acme.shared.payload.MemberPrincipal;
import com.acme.shared.payload.PagedResponse;
import com.acme.shared.payload.audit.AuditDTO;
import com.acme.shared.payload.table.FilterDTO;
import com.acme.shared.payload.table.SortDTO;
import com.acme.shared.util.MsgUtil;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

public class PagedQueryBuilder<T, R> {

  private final DatabaseClient databaseClient;
  private final MsgUtil        msgUtil;
  private String               baseQuery;
  private String               countQuery;
  private Function<Row, T>     rowMapper;
  private Function<List<T>, Flux<R>> converter;
  private Function<T, Long>    cursorExtractor;

  private BiFunction<Long, String, Mono<UserViewResult>> viewLoader;
  private Long   viewMemberId;
  private String viewEntityClass;

  private static final ConcurrentHashMap<String, long[]> COUNT_CACHE  = new ConcurrentHashMap<>();
  private static final long                              CACHE_TTL_MS = 30_000L;

  private static final String LOAD_VIEW_SQL = """
      SELECT filters, sorts, view_id
      FROM   views
      WHERE  member_id = :memberId
      AND    entity    = :entity
      LIMIT  1
      """;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMINISTRATOR", "DEVELOPER");

  // ─── Audit context resolution ─────────────────────────────────────────────

  private Mono<AuditDTO> resolveAudit() {
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
  // ─── UserViewResult ───────────────────────────────────────────────────────

  public record UserViewResult(String viewId, List<FilterDTO> filters, List<SortDTO> sorts) {
    public static UserViewResult empty() { return new UserViewResult(null, null, null); }
  }

  // ─── Constructor ──────────────────────────────────────────────────────────

  public PagedQueryBuilder(DatabaseClient databaseClient, MsgUtil msgUtil) {
    this.databaseClient = databaseClient;
    this.msgUtil        = msgUtil;
  }

  // ─── Builder methods ──────────────────────────────────────────────────────

  public PagedQueryBuilder<T, R> baseQuery(String query)               { this.baseQuery = query;    return this; }
  public PagedQueryBuilder<T, R> countQuery(String query)              { this.countQuery = query;   return this; }
  public PagedQueryBuilder<T, R> cursorExtractor(Function<T, Long> e)  { this.cursorExtractor = e; return this; }

  public void rowMapper(Function<Row, T> m)            { this.rowMapper  = m; }
  public void converter(Function<List<T>, Flux<R>> c)  { this.converter  = c; }


  public PagedQueryBuilder<T, R> viewLoader(Long memberId, String entity,
                                            BiFunction<Long, String, Mono<UserViewResult>> loader) {
    this.viewMemberId    = memberId;
    this.viewEntityClass = entity;
    this.viewLoader      = loader;
    return this;
  }

  public PagedQueryBuilder<T, R> viewQuery(Long memberId, String entity) {
    if (memberId == null || memberId <= 0 || entity == null || entity.isBlank()) {
      return this;
    }
    this.viewMemberId    = memberId;
    this.viewEntityClass = entity;
    this.viewLoader      = (mid, ent) ->
        databaseClient.sql(LOAD_VIEW_SQL)
            .bind("memberId", mid)
            .bind("entity",   ent)
            .map((row, meta) -> {
              String filtersJson = row.get("filters",  String.class);
              String sortsJson   = row.get("sorts",    String.class);
              String viewId      = row.get("view_id",  String.class);
              return new UserViewResult(
                  viewId,
                  parseList(filtersJson, FilterDTO.class),
                  parseList(sortsJson,   SortDTO.class)
              );
            })
            .one()                                       // empty Mono when no row
            .defaultIfEmpty(UserViewResult.empty())
            .onErrorReturn(UserViewResult.empty());      // never fail the page query
    return this;
  }

  // ─── Execute ──────────────────────────────────────────────────────────────

  public Mono<PagedResponse<R>> execute(PageQueryParams params) {
    return params.isCursorMode() ? executeCursor(params) : executeOffset(params);
  }

  public Mono<PagedResponse<R>> executeOffset(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(
            AuditDTO.builder().build())
        .flatMap(audit -> {
          String dataQuery = buildDataQuery(params, audit);
          String cQuery    = countQuery != null ? buildCountQueryCustom(params, audit) : buildCountQueryAuto(params, audit);
          String cacheKey  = cQuery + "|" + buildWhereClause(params, audit);

          DatabaseClient.GenericExecuteSpec dataSpec  = databaseClient.sql(dataQuery);
          DatabaseClient.GenericExecuteSpec countSpec = databaseClient.sql(cQuery);

          dataSpec  = bindParameters(dataSpec,  params, audit);
          countSpec = bindParameters(countSpec, params, audit);

          Flux<T>    dataFlux  = dataSpec.map((row, meta) -> rowMapper.apply(row)).all();
          Mono<Long> countMono = getCachedCount(cacheKey, countSpec);
          Mono<UserViewResult> viewMono = viewLoader != null
              ? viewLoader.apply(viewMemberId, viewEntityClass).onErrorReturn(UserViewResult.empty())
              : Mono.just(UserViewResult.empty());

          return Mono.zip(dataFlux.collectList(), countMono, viewMono)
              .flatMap(tuple -> {
                List<T>        results    = tuple.getT1();
                long           total      = tuple.getT2();
                UserViewResult view       = tuple.getT3();
                int            totalPages = (int) Math.ceil((double) total / params.getSize());

                if (results.isEmpty()) {
                  return msgUtil.get("page.response.empty")
                      .map(msg -> PagedResponse.<R>success(msg, params.getIndex(), params.getSize()));
                }
                return converter.apply(results).collectList()
                    .flatMap(dtoList -> msgUtil.get("page.response.fetch.success")
                        .map(msg -> PagedResponse.<R>success(
                            msg, dtoList, totalPages, total,
                            params.getIndex(), params.getSize(),
                            view.filters(), view.sorts(), view.viewId())));
              });
        })
        .onErrorResume(e -> msgUtil.get("page.response.failed")
            .map(msg -> PagedResponse.<R>error(msg, e.getMessage())));
  }

  // ─── List (load-more / infinite scroll) ──────────────────────────────────

  public Mono<ListResponse<List<R>>> executeList(PageQueryParams params) {
    if (params.hasSelectIds() && params.getIndex() == 0
        && (params.getKeyword() == null || params.getKeyword().isBlank())) {
      return executeListWithSelectIds(params);
    }
    return executeListNormal(params);
  }

  private Mono<ListResponse<List<R>>> executeListNormal(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(
            AuditDTO.builder().build())
        .flatMap(audit -> {
          DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(
              buildDataQuery(params, audit, (params.getSize() + 1), (long) params.getIndex() * params.getSize()));
          spec = bindParameters(spec, params, audit);

          return spec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(results -> {
                boolean hasMore = results.size() > params.getSize();
                List<T> actual  = hasMore ? results.subList(0, params.getSize()) : results;
                if (actual.isEmpty()) {
                  return msgUtil.get("list.response.empty").map(ListResponse::<List<R>>success);
                }
                return converter.apply(actual).collectList()
                    .flatMap(dtoList -> msgUtil.get("list.response.fetch.success")
                        .map(msg -> ListResponse.success(msg, hasMore, dtoList)));
              });
        })
        .onErrorResume(e -> msgUtil.get("list.response.failed")
            .map(msg -> ListResponse.error(msg, e.getMessage())));
  }

  private Mono<ListResponse<List<R>>> executeListWithSelectIds(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(
            AuditDTO.builder().build())
        .flatMap(audit -> {
          DatabaseClient.GenericExecuteSpec selectSpec =
              bindSelectIdsParameters(databaseClient.sql(buildSelectIdsQuery(params)), params);

          return selectSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(pinned -> {
                int fetchSize = params.getSize() + params.getSelectIds().size() + 1;
                DatabaseClient.GenericExecuteSpec dataSpec =
                    bindParameters(databaseClient.sql(buildDataQuery(params, audit, fetchSize, 0L)), params, audit);

                return dataSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
                    .flatMap(rest -> {
                      Map<Object, T> seen = new LinkedHashMap<>();
                      for (T item : pinned) { Object id = extractId(item); if (id != null) seen.put(id, item); }
                      for (T item : rest)   { Object id = extractId(item); seen.putIfAbsent(id, item); }
                      List<T> merged  = new ArrayList<>(seen.values());
                      boolean hasMore = merged.size() > params.getSize();
                      List<T> actual  = hasMore ? merged.subList(0, params.getSize()) : merged;
                      if (actual.isEmpty()) {
                        return msgUtil.get("list.response.empty").map(ListResponse::<List<R>>success);
                      }
                      return converter.apply(actual).collectList()
                          .flatMap(dtoList -> msgUtil.get("list.response.fetch.success")
                              .map(msg -> ListResponse.success(msg, hasMore, dtoList)));
                    });
              });
        })
        .onErrorResume(e -> msgUtil.get("list.response.failed")
            .map(msg -> ListResponse.error(msg, e.getMessage())));
  }

  private Mono<PagedResponse<R>> executeCursor(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(
            AuditDTO.builder().build())
        .flatMap(audit -> {
          DatabaseClient.GenericExecuteSpec dataSpec =
              bindParameters(databaseClient.sql(buildCursorDataQuery(params, audit)), params, audit);

          if (params.hasCursor()) dataSpec = dataSpec.bind("cursor", params.getCursor());

          return dataSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(results -> {
                boolean hasNext     = results.size() > params.getSize();
                List<T> pageResults = hasNext ? results.subList(0, params.getSize()) : results;

                if (pageResults.isEmpty()) {
                  return msgUtil.get("page.response.empty")
                      .map(msg -> PagedResponse.<R>cursorSuccess(msg, List.<R>of(), false));
                }
                Long nextCursor = cursorExtractor != null
                    ? cursorExtractor.apply(pageResults.getLast())
                    : null;

                return converter.apply(pageResults).collectList()
                    .flatMap(dtoList -> msgUtil.get("page.response.fetch.success")
                        .map(msg -> PagedResponse.<R>cursorSuccess(msg, dtoList, nextCursor, hasNext)));
              });
        })
        .onErrorResume(e -> msgUtil.get("page.response.failed")
            .map(msg -> PagedResponse.<R>error(msg, e.getMessage())));
  }

  // ─── Query builders ───────────────────────────────────────────────────────

  private String buildDataQuery(PageQueryParams params, AuditDTO audit) {
    return buildDataQuery(params, audit, params.getSize(), (long) params.getIndex() * params.getSize());
  }

  private String buildDataQuery(PageQueryParams params, AuditDTO audit, long limit, long offset) {
    StringBuilder q        = new StringBuilder(baseQuery);
    String        where    = buildWhereClause(params, audit);
    boolean       hasWhere = baseQuery.toUpperCase().contains(" WHERE ");
    if (!where.isEmpty()) q.append(hasWhere ? " AND " : " WHERE ").append(where);
    if (params.getOrderBy() != null && !params.getOrderBy().isEmpty()) {
      q.append(" ORDER BY ").append(params.getOrderBy())
          .append(params.isDescending() ? " DESC" : " ASC");
    }
    q.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
    return q.toString();
  }

  private String buildCursorDataQuery(PageQueryParams params, AuditDTO audit) {
    StringBuilder q          = new StringBuilder(baseQuery);
    List<String>  conditions = new ArrayList<>();
    String        where      = buildWhereClause(params, audit);

    if (!where.isEmpty()) conditions.add(where);
    if (params.hasCursor()) {
      String op = params.isDescending() ? "<" : ">";
      conditions.add(params.getCursorField() + " " + op + " :cursor");
    }
    boolean hasWhere = baseQuery.toUpperCase().contains(" WHERE ");
    if (!conditions.isEmpty()) {
      q.append(hasWhere ? " AND " : " WHERE ").append(String.join(" AND ", conditions));
    }
    String orderField = params.getOrderBy() != null ? params.getOrderBy() : params.getCursorField();
    q.append(" ORDER BY ").append(orderField).append(params.isDescending() ? " DESC" : " ASC");
    q.append(" LIMIT ").append((long) params.getSize() + 1);
    return q.toString();
  }

  private String buildCountQueryAuto(PageQueryParams params, AuditDTO audit) {
    StringBuilder inner = new StringBuilder(baseQuery);
    String where = buildWhereClause(params, audit);
    boolean hasWhere = baseQuery.toUpperCase().contains(" WHERE ");
    if (!where.isEmpty()) inner.append(hasWhere ? " AND " : " WHERE ").append(where);
    return "SELECT COUNT(*) FROM (" + inner + ") AS _count_subquery";
  }

  private String buildCountQueryCustom(PageQueryParams params, AuditDTO audit) {
    StringBuilder q = new StringBuilder(countQuery);
    String where = buildWhereClause(params, audit);
    if (!where.isEmpty()) q.append(" WHERE ").append(where);
    return q.toString();
  }

  private String buildSelectIdsQuery(PageQueryParams params) {
    StringBuilder q  = new StringBuilder(baseQuery).append(" WHERE id IN (");
    List<String>  ph = new ArrayList<>();
    for (int i = 0; i < params.getSelectIds().size(); i++) ph.add(":select_id_" + i);
    q.append(String.join(", ", ph)).append(") ORDER BY CASE id ");
    for (int i = 0; i < params.getSelectIds().size(); i++)
      q.append("WHEN :select_id_").append(i).append(" THEN ").append(i).append(" ");
    return q.append("END").toString();
  }

  private String buildWhereClause(PageQueryParams params, AuditDTO audit) {
    List<String> conditions = new ArrayList<>();

    // ─── Audit scope ──────────────────────────────────────────────────────────
    if (params.isAuditEnabled() && audit != null && audit.getRoleType() != null) {
      if (!PRIVILEGED_ROLES.contains(audit.getRoleType().name())) {
        conditions.add(params.getAuditDepartmentField() + " = :audit_department_id");
      }
    }

    if (params.hasKeywordSearch()) {
      List<String> sc = new ArrayList<>();
      for (String field : params.getSearchFields())
        sc.add("LOWER(" + field + ") LIKE :search_" + sanitize(field));
      if (params.hasIdSearch()) {
        String idCol = params.getIdField();
        if (params.isKeywordNumeric()) sc.add(idCol + " = :id_exact");
        sc.add("CAST(" + idCol + " AS TEXT) LIKE :id_like");
      }
      if (!sc.isEmpty()) conditions.add("(" + String.join(" OR ", sc) + ")");
    } else if (params.hasIdSearch()) {
      String       idCol  = params.getIdField();
      List<String> idCons = new ArrayList<>();
      if (params.isKeywordNumeric()) idCons.add(idCol + " = :id_exact");
      idCons.add("CAST(" + idCol + " AS TEXT) LIKE :id_like");
      conditions.add("(" + String.join(" OR ", idCons) + ")");
    }

    if (params.hasFtsSearch())
      conditions.add(params.getFtsField() + " @@ plainto_tsquery('simple', :fts_query)");

    params.getFilters().keySet().forEach(f -> conditions.add(f + " = :filter_" + f));

    params.getInFilters().forEach((field, values) -> {
      if (values != null && !values.isEmpty()) {
        List<String> ph = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) ph.add(":in_" + field + "_" + i);
        conditions.add(field + " IN (" + String.join(", ", ph) + ")");
      }
    });

    params.getDateRanges().forEach((field, range) -> {
      if (range.getStartDate() != null && range.getEndDate() != null)
        conditions.add(field + " BETWEEN :start_" + field + " AND :end_" + field);
      else if (range.getStartDate() != null)
        conditions.add(field + " >= :start_" + field);
      else if (range.getEndDate() != null)
        conditions.add(field + " <= :end_" + field);
    });

    if (params.hasColumnFilters()) {
      ColumnFilterClause clause = new ColumnFilterClause(
          params.getColumnFilters(), params.getAllowedFilterFields());
      String colSql = clause.toSql();
      if (!colSql.isEmpty()) conditions.add(colSql);
    }

    // ─── Soft-delete filter ───────────────────────────────────────────────────
    if (params.isCheckDeleteEnabled() && audit != null && audit.getRoleType() != null) {
      if (!PRIVILEGED_ROLES.contains(audit.getRoleType().name())) {
        conditions.add(params.getDeleteField() + " = false");
      }
    }

    return String.join(" AND ", conditions);
  }

  // ─── Parameter binding ────────────────────────────────────────────────────

  private DatabaseClient.GenericExecuteSpec bindParameters(
      DatabaseClient.GenericExecuteSpec spec, PageQueryParams params) {
    return bindParameters(spec, params, null);
  }

  private DatabaseClient.GenericExecuteSpec bindParameters(
      DatabaseClient.GenericExecuteSpec spec, PageQueryParams params, AuditDTO audit) {

    // ─── Audit department binding ─────────────────────────────────────────
    if (params.isAuditEnabled() && audit != null && audit.getRoleType() != null) {
      if (!PRIVILEGED_ROLES.contains(audit.getRoleType().name())) {
        Long deptId = audit.getDepartmentId() != null ? audit.getDepartmentId() : 0L;
        spec = spec.bind("audit_department_id", deptId);
      }
    }

    if (params.hasKeywordSearch()) {
      String pattern = "%" + params.getKeyword().toLowerCase() + "%";
      for (String field : params.getSearchFields())
        spec = spec.bind("search_" + sanitize(field), pattern);
    }

    if (params.hasIdSearch() && params.isKeywordNumeric()) {
      spec = spec.bind("id_exact", Long.parseLong(params.getKeyword().trim()));
      spec = spec.bind("id_like",  "%" + params.getKeyword().trim() + "%");
    } else if (params.hasIdSearch()) {
      spec = spec.bind("id_like", "%" + params.getKeyword().trim() + "%");
    }

    if (params.hasFtsSearch())
      spec = spec.bind("fts_query", params.getFtsQuery());

    for (Map.Entry<String, Object> e : params.getFilters().entrySet())
      spec = spec.bind("filter_" + e.getKey(), e.getValue());

    for (Map.Entry<String, Collection<?>> e : params.getInFilters().entrySet()) {
      int i = 0;
      for (Object v : e.getValue()) spec = spec.bind("in_" + e.getKey() + "_" + i++, v);
    }

    for (Map.Entry<String, DateRange> e : params.getDateRanges().entrySet()) {
      DateRange range = e.getValue();
      if (range.getStartDate() != null) spec = spec.bind("start_" + e.getKey(), range.getStartDate());
      if (range.getEndDate()   != null) spec = spec.bind("end_"   + e.getKey(), range.getEndDate());
    }

    if (params.hasColumnFilters()) {
      ColumnFilterClause clause = new ColumnFilterClause(
          params.getColumnFilters(), params.getAllowedFilterFields());
      spec = clause.bind(spec);
    }
    return spec;
  }

  private DatabaseClient.GenericExecuteSpec bindSelectIdsParameters(
      DatabaseClient.GenericExecuteSpec spec, PageQueryParams params) {
    for (int i = 0; i < params.getSelectIds().size(); i++)
      spec = spec.bind("select_id_" + i, params.getSelectIds().get(i));
    return spec;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private Mono<Long> getCachedCount(String cacheKey, DatabaseClient.GenericExecuteSpec countSpec) {
    long[] cached = COUNT_CACHE.get(cacheKey);
    if (cached != null && System.currentTimeMillis() < cached[1]) return Mono.just(cached[0]);
    return countSpec.map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L)
        .doOnNext(count -> COUNT_CACHE.put(cacheKey,
            new long[]{count, System.currentTimeMillis() + CACHE_TTL_MS}));
  }

  private Object extractId(T item) {
    try { return item.getClass().getMethod("getId").invoke(item); }
    catch (Exception e) { return null; }
  }

  private String sanitize(String fieldName) {
    return fieldName.replaceAll("\\W", "_");
  }

  @SuppressWarnings("unchecked")
  private <E> List<E> parseList(String json, Class<E> type) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return (List<E>) MAPPER.readValue(
          json,
          MAPPER.getTypeFactory().constructCollectionType(List.class, type)
      );
    } catch (Exception e) {
      return List.of();
    }
  }
}