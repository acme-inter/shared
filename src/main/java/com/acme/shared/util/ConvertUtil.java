package com.acme.shared.util;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public class ConvertUtil {

  public static Long parseLong(String value) {
    try { return value != null ? Long.parseLong(value.trim()) : null; }
    catch (NumberFormatException e) { return null; }
  }

  public static Integer parseInt(String value) {
    try { return value != null ? Integer.parseInt(value.trim()) : null; }
    catch (NumberFormatException e) { return null; }
  }

  public static Double parseDouble(String value) {
    try { return value != null ? Double.parseDouble(value.trim()) : null; }
    catch (NumberFormatException e) { return null; }
  }

  public static BigDecimal parseBigDecimal(String value) {
    try { return value != null ? new BigDecimal(value.trim()) : null; }
    catch (NumberFormatException e) { return null; }
  }

  public static BigDecimal parseBigDecimal(Double value) {
    return value != null ? BigDecimal.valueOf(value) : null;
  }

  public static BigDecimal parseBigDecimal(Long value) {
    return value != null ? BigDecimal.valueOf(value) : null;
  }

  public static BigDecimal parseBigDecimal(Integer value) {
    return value != null ? BigDecimal.valueOf(value) : null;
  }

  public static Boolean parseBoolean(String value) {
    if (value == null) return false;
    return switch (value.trim().toLowerCase()) {
      case "true",  "1", "yes", "y" -> true;
      case "false", "0", "no",  "n" -> false;
      default -> null;
    };
  }

  public static String dateTimeFormat(String dateTime) {
    if (dateTime == null) return null;
    OffsetDateTime odt = OffsetDateTime.parse(dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
  }

  public static String dateTimeFormat(String dateTime, String outputPattern) {
    if (dateTime == null) return null;
    OffsetDateTime odt = OffsetDateTime.parse(dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    return odt.format(DateTimeFormatter.ofPattern(outputPattern));
  }

  public static LocalDate parseLocalDate(String value) {
    try { return value != null ? LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE) : null; }
    catch (DateTimeParseException e) { return null; }
  }

  public static LocalDateTime parseLocalDateTime(String value) {
    try { return value != null ? LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null; }
    catch (DateTimeParseException e) { return null; }
  }

  public static String formatDate(LocalDate date, String pattern) {
    return date != null ? date.format(DateTimeFormatter.ofPattern(pattern)) : null;
  }

  public static String formatDateTime(LocalDateTime dateTime, String pattern) {
    return dateTime != null ? dateTime.format(DateTimeFormatter.ofPattern(pattern)) : null;
  }

  public static String trimToNull(String value) {
    if (value == null) return null;
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }

  public static String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  public static String toUpperCase(String value) {
    return value != null ? value.trim().toUpperCase() : null;
  }

  public static String toLowerCase(String value) {
    return value != null ? value.trim().toLowerCase() : null;
  }


  public static Long toLongOrDefault(String value, Long defaultValue) {
    Long r = parseLong(value); return r != null ? r : defaultValue;
  }

  public static Integer toIntOrDefault(String value, Integer defaultValue) {
    Integer r = parseInt(value); return r != null ? r : defaultValue;
  }

  public static Double toDoubleOrDefault(String value, Double defaultValue) {
    Double r = parseDouble(value); return r != null ? r : defaultValue;
  }

  public static BigDecimal toBigDecimalOrDefault(String value, BigDecimal defaultValue) {
    BigDecimal r = parseBigDecimal(value); return r != null ? r : defaultValue;
  }

  public String buildWeekLabel(LocalDate s, LocalDate e) {
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d");
    return fmt.format(s) + " – " + fmt.format(e) + ", " + e.getYear();
  }

  public String timeAgo(Instant ts) {
    long secs = ChronoUnit.SECONDS.between(ts, Instant.now());
    if (secs < 60)    return "just now";
    if (secs < 3600)  return (secs / 60) + " min ago";
    if (secs < 86400) return (secs / 3600) + " hr ago";
    return (secs / 86400) + " days ago";
  }

  public int       toInt(Object v)       { return v == null ? 0 : ((Number) v).intValue(); }
  public long      toLong(Object v)      { return v == null ? 0L : ((Number) v).longValue(); }
  public double    toDouble(Object v)    { return v == null ? 0.0 : ((Number) v).doubleValue(); }
  public String    str(Object v)         { return v == null ? "" : v.toString().trim(); }
  public LocalDate toLocalDate(Object v) {
    if (v == null) return LocalDate.now();
    if (v instanceof LocalDate d) return d;
    String s = v.toString();
    return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
  }
}
