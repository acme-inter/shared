package com.acme.shared.query;

import com.acme.shared.payload.table.FilterDTO;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Getter
public class PageQueryParams {

  private final String                     keyword;
  private final List<String>               searchFields;
  private final Map<String, Object>        filters;
  private final Map<String, Collection<?>> inFilters;
  private final Map<String, DateRange>     dateRanges;
  private final String                     ftsField;
  private final String                     ftsQuery;
  private final int                        index;
  private final int                        size;
  private final String                     orderBy;
  private final boolean                    descending;
  private final Long                       cursor;
  private final String                     cursorField;
  private final boolean                    cursorMode;
  private final String                     idField;
  private final List<Long>                 selectIds;
  private final List<FilterDTO>            columnFilters;
  private final Set<String>                allowedFilterFields;

  // ─── Pinned-ids-first ordering ───────────────────────────────────────────
  /** When set, these ids are floated to the top of ORDER BY (in list order)
   *  regardless of the normal sort. Safe: values are Long, never interpolated as strings. */
  private final List<Long> pinnedIds;

  // ─── Audit-scope fields ───────────────────────────────────────────────────
  /** When true, PagedQueryBuilder resolves the caller's role and department from
   *  the security context and automatically scopes the WHERE clause. */
  private final boolean auditEnabled;
  /** Column name in the view/table that holds the owning department id.
   *  Defaults to "department_id". */
  private final String  auditDepartmentField;

  // ─── Soft-delete visibility ───────────────────────────────────────────────
  /** When true, soft-delete filtering is active.
   *  Privileged roles (ADMINISTRATOR / DEVELOPER) see all rows;
   *  all other roles only see rows where {@code deleteField = false}. */
  private final boolean checkDeleteEnabled;
  /** Column name that holds the soft-delete flag. Defaults to "is_deleted". */
  private final String  deleteField;

  private PageQueryParams(Builder b) {
    this.keyword             = b.keyword;
    this.searchFields        = List.copyOf(b.searchFields);
    this.filters             = Map.copyOf(b.filters);
    this.inFilters           = Map.copyOf(b.inFilters);
    this.dateRanges          = Map.copyOf(b.dateRanges);
    this.ftsField            = b.ftsField;
    this.ftsQuery            = b.ftsQuery;
    this.index               = Math.max(0, b.index);
    this.size                = Math.clamp(b.size, 1, 1000);
    this.orderBy             = b.orderBy;
    this.descending          = b.descending;
    this.cursor              = b.cursor;
    this.cursorField         = b.cursorField != null ? b.cursorField : "id";
    this.cursorMode          = b.cursorMode;
    this.idField             = b.idField;
    this.selectIds           = b.selectIds != null ? List.copyOf(b.selectIds) : List.of();
    this.columnFilters       = b.columnFilters != null ? List.copyOf(b.columnFilters) : List.of();
    this.allowedFilterFields = b.allowedFilterFields != null
        ? Set.copyOf(b.allowedFilterFields) : Set.of();
    this.pinnedIds           = b.pinnedIds != null ? List.copyOf(b.pinnedIds) : List.of();
    this.auditEnabled        = b.auditEnabled;
    this.auditDepartmentField = b.auditDepartmentField != null
        ? b.auditDepartmentField : "department_id";
    this.checkDeleteEnabled  = b.checkDeleteEnabled;
    this.deleteField         = b.deleteField != null ? b.deleteField : "is_deleted";
  }

  // ─── Explicit getters ────────────────────────────────────────────────────

  // ─── Derived predicates ──────────────────────────────────────────────────

  public boolean hasPinnedIds()      { return !pinnedIds.isEmpty(); }
  public boolean hasColumnFilters()  { return !columnFilters.isEmpty(); }
  public boolean hasSelectIds()      { return !selectIds.isEmpty(); }
  public boolean hasCursor()         { return cursor != null && cursor > 0; }
  public boolean hasFtsSearch()      { return ftsField != null && ftsQuery != null && !ftsQuery.isBlank(); }
  public boolean hasKeywordSearch()  { return keyword != null && !keyword.trim().isEmpty() && !searchFields.isEmpty(); }
  public boolean hasIdSearch()       { return idField != null && keyword != null && !keyword.trim().isEmpty(); }
  public boolean isKeywordNumeric()  { return keyword != null && keyword.trim().matches("\\d+"); }

  public static Builder builder() { return new Builder(); }

  // ─── Builder ─────────────────────────────────────────────────────────────

  public static class Builder {
    private String                           keyword;
    private List<String>                     searchFields         = new ArrayList<>();
    private final Map<String, Object>        filters              = new HashMap<>();
    private final Map<String, Collection<?>> inFilters            = new HashMap<>();
    private final Map<String, DateRange>     dateRanges           = new HashMap<>();
    private String                           ftsField;
    private String                           ftsQuery;
    private int                              index                = 0;
    private int                              size                 = 10;
    private String                           orderBy;
    private boolean                          descending           = true;
    private Long                             cursor;
    private String                           cursorField;
    private boolean                          cursorMode           = false;
    private String                           idField;
    private List<Long>                       selectIds            = new ArrayList<>();
    private List<FilterDTO>                  columnFilters        = new ArrayList<>();
    private Set<String>                      allowedFilterFields  = new HashSet<>();
    private boolean                          auditEnabled         = false;
    private String                           auditDepartmentField;
    private List<Long>                       pinnedIds            = new ArrayList<>();
    private boolean                          checkDeleteEnabled   = false;
    private String                           deleteField;

    public Builder keyword(String keyword)            { this.keyword = keyword; return this; }
    public Builder searchFields(String... fields)     { this.searchFields = new ArrayList<>(Arrays.asList(fields)); return this; }
    public Builder searchFields(List<String> fields)  { this.searchFields = new ArrayList<>(fields); return this; }
    public Builder idField(String field)              { this.idField = field; return this; }
    public Builder index(int index)                   { this.index = index; return this; }
    public Builder size(int size)                     { this.size = size; return this; }
    public Builder orderBy(String field)              { this.orderBy = field; return this; }
    public Builder ascending()                        { this.descending = false; return this; }
    public Builder descending()                       { this.descending = true; return this; }
    public Builder cursorMode()                       { this.cursorMode = true; return this; }
    public Builder cursorField(String field)          { this.cursorField = field; return this; }


    public Builder pinnedIds(List<Long> ids) {
      if (ids != null && !ids.isEmpty()) this.pinnedIds = new ArrayList<>(ids);
      return this;
    }

    public Builder auditEnable() {
      this.auditEnabled = true;
      return this;
    }

    public Builder auditDepartmentField(String field) {
      this.auditDepartmentField = field;
      this.auditEnabled         = true;
      return this;
    }

    /** Enable soft-delete visibility control (column defaults to "is_deleted"). */
    public Builder checkDelete() {
      this.checkDeleteEnabled = true;
      return this;
    }

    /** Enable soft-delete visibility control with a custom column name. */
    public Builder checkDelete(String field) {
      this.checkDeleteEnabled = true;
      this.deleteField        = field;
      return this;
    }

    public Builder cursor(Long cursor) {
      this.cursor     = cursor;
      this.cursorMode = true;
      return this;
    }

    public Builder filter(String field, Object value) {
      if (value != null) this.filters.put(field, value);
      return this;
    }

    public Builder filters(Map<String, Object> filters) {
      if (filters != null) filters.forEach((k, v) -> { if (v != null) this.filters.put(k, v); });
      return this;
    }

    public void filterIn(String field, Collection<?> values) {
      if (values != null && !values.isEmpty()) this.inFilters.put(field, values);
    }

    public Builder filterIn(String field, Object... values) {
      if (values != null && values.length > 0) this.inFilters.put(field, Arrays.asList(values));
      return this;
    }

    public Builder dateRange(String field, DateRange range) {
      if (range != null) this.dateRanges.put(field, range);
      return this;
    }

    public Builder dateRange(String field, LocalDate start, LocalDate end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    public Builder dateRange(String field, LocalDateTime start, LocalDateTime end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    public Builder dateRange(String field, Instant start, Instant end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    public Builder ftsSearch(String field, String query) {
      if (field != null && query != null && !query.isBlank()) {
        this.ftsField = field;
        this.ftsQuery = query;
      }
      return this;
    }

    public Builder selectIds(List<Long> ids) {
      if (ids != null && !ids.isEmpty()) this.selectIds = new ArrayList<>(ids);
      return this;
    }

    public Builder columnFilters(List<FilterDTO> filters) {
      if (filters != null) this.columnFilters = new ArrayList<>(filters);
      return this;
    }

    public Builder allowedFilterFields(String... fields) {
      this.allowedFilterFields = new HashSet<>(Arrays.asList(fields));
      return this;
    }

    public Builder allowedFilterFields(Set<String> fields) {
      this.allowedFilterFields = new HashSet<>(fields);
      return this;
    }

    public PageQueryParams build() { return new PageQueryParams(this); }
  }
}