package com.acme.shared.query;

import com.acme.shared.enums.FilterOperator;
import com.acme.shared.payload.table.FilterDTO;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ColumnFilterClause {

  private final List<FilterDTO> filters;
  private final Set<String>     allowedFields;

  // Column name suffixes that hold timestamp/date values
  private static final List<String> DATE_FIELD_SUFFIXES = List.of(
      "_at", "_date", "_time", "_on"
  );

  public ColumnFilterClause(List<FilterDTO> filters, Set<String> allowedFields) {
    this.filters       = filters       == null ? List.of() : filters;
    this.allowedFields = allowedFields == null ? Set.of()  : allowedFields;
  }

  public boolean isEmpty() { return filters.isEmpty(); }

  public String toSql() {
    if (filters.isEmpty()) return "";
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < filters.size(); i++) {
      parts.add(buildFragment(filters.get(i), i));
    }
    return String.join(" AND ", parts);
  }

  public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec) {
    for (int i = 0; i < filters.size(); i++) {
      spec = bindFilter(spec, filters.get(i), i);
    }
    return spec;
  }

  // ─── Private ──────────────────────────────────────────────────────────────

  private String buildFragment(FilterDTO filter, int idx) {
    // normalise camelCase → snake_case before validation and SQL
    String field  = toSnakeCase(filter.getField());
    validateField(field);

    FilterOperator op     = filter.operator();
    op.validate(filter);
    String prefix = "cf_" + idx + "_";
    boolean isDate = isDateField(field);

    return switch (op) {
      case CONTAINS     -> "(" + lower(field) + " LIKE CONCAT('%', LOWER(:" + prefix + "v), '%'))";
      case NOT_CONTAINS -> "(" + lower(field) + " NOT LIKE CONCAT('%', LOWER(:" + prefix + "v), '%'))";
      case STARTS_WITH  -> "(" + lower(field) + " LIKE CONCAT(LOWER(:" + prefix + "v), '%'))";
      case ENDS_WITH    -> "(" + lower(field) + " LIKE CONCAT('%', LOWER(:" + prefix + "v)))";
      case EQ           -> "(" + field + " = :"  + prefix + "v)";
      case NEQ          -> "(" + field + " != :" + prefix + "v)";
      case IS_NULL      -> "(" + field + " IS NULL)";
      case IS_NOT_NULL  -> "(" + field + " IS NOT NULL)";
      case GT           -> isDate
          ? "(" + field + " > (:"   + prefix + "v)::timestamptz)"
          : "(" + field + " > :"    + prefix + "v)";
      case GTE          -> isDate
          ? "(" + field + " >= (:"  + prefix + "v)::timestamptz)"
          : "(" + field + " >= :"   + prefix + "v)";
      case LT           -> isDate
          ? "(" + field + " < (:"   + prefix + "v)::timestamptz)"
          : "(" + field + " < :"    + prefix + "v)";
      case LTE          -> isDate
          ? "(" + field + " <= (:"  + prefix + "v)::timestamptz)"
          : "(" + field + " <= :"   + prefix + "v)";
      case BETWEEN      -> isDate
          ? "(" + field + " BETWEEN (:" + prefix + "v0)::timestamptz"
          + " AND (:"     + prefix + "v1)::timestamptz)"
          : "(" + field + " BETWEEN :"  + prefix + "v0 AND :" + prefix + "v1)";
      case IN           -> "(" + field + " IN ("     + inPlaceholders(prefix, filter.getValues().size()) + "))";
      case NOT_IN       -> "(" + field + " NOT IN (" + inPlaceholders(prefix, filter.getValues().size()) + "))";
    };
  }

  private DatabaseClient.GenericExecuteSpec bindFilter(
      DatabaseClient.GenericExecuteSpec spec, FilterDTO filter, int idx) {

    FilterOperator op     = filter.operator();
    String         prefix = "cf_" + idx + "_";

    switch (op) {
      case IS_NULL, IS_NOT_NULL -> { /* no bindings */ }
      case CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH,
           EQ, NEQ, GT, GTE, LT, LTE ->
          spec = spec.bind(prefix + "v", filter.getValue());
      case BETWEEN -> {
        spec = spec.bind(prefix + "v0", filter.getValues().get(0));
        spec = spec.bind(prefix + "v1", filter.getValues().get(1));
      }
      case IN, NOT_IN -> {
        List<Object> vals = filter.getValues();
        for (int j = 0; j < vals.size(); j++) {
          spec = spec.bind(prefix + "v" + j, vals.get(j));
        }
      }
    }
    return spec;
  }

  private void validateField(String field) {
    if (field == null || field.isBlank()) {
      throw new IllegalArgumentException("Filter field must not be blank.");
    }
    if (!allowedFields.isEmpty() && !allowedFields.contains(field)) {
      throw new IllegalArgumentException(
          "Filter field '" + field + "' is not permitted. Allowed: " + allowedFields);
    }
  }

  private String toSnakeCase(String field) {
    if (field == null) return null;
    return field
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .toLowerCase();
  }

  private boolean isDateField(String snakeField) {
    if (snakeField == null) return false;
    for (String suffix : DATE_FIELD_SUFFIXES) {
      if (snakeField.endsWith(suffix)) return true;
    }
    return false;
  }

  private String inPlaceholders(String prefix, int count) {
    List<String> ph = new ArrayList<>();
    for (int j = 0; j < count; j++) ph.add(":" + prefix + "v" + j);
    return String.join(", ", ph);
  }

  private String lower(String field) { return "LOWER(" + field + ")"; }
}
