package com.acme.shared.service;

import com.acme.shared.payload.calendar.CreateEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class SharedCalendarService {

  private static final String BASE = "https://www.googleapis.com/calendar/v3";

  private final WebClient webClient;
  private final Function<Long, Mono<String>> tokenProvider;

  public Mono<JsonNode> listCalendars(Long memberId) {
    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/users/me/calendarList")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> listEvents(Long memberId, String calendarId, int maxResults, String timeMin, String timeMax) {
    String tMin = timeMin != null ? timeMin : Instant.now().toString();
    String tMax = timeMax != null ? timeMax : Instant.now().plus(30, ChronoUnit.DAYS).toString();

    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/calendars/{cal}/events?maxResults={max}"
                + "&timeMin={tMin}&timeMax={tMax}&orderBy=startTime&singleEvents=true",
            calendarId, maxResults, tMin, tMax)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> getEvent(Long memberId, String calendarId, String eventId) {
    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/calendars/{cal}/events/{eventId}", calendarId, eventId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> createEvent(Long memberId, String calendarId, CreateEventDTO req) {
    Map<String, Object> body = buildEventBody(req);
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/calendars/{cal}/events", calendarId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  /**
   * Fully replaces an existing event (PUT).
   * All fields must be supplied — missing fields are cleared.
   */
  public Mono<JsonNode> updateEvent(Long memberId, String calendarId,
                                    String eventId, CreateEventDTO req) {
    Map<String, Object> body = buildEventBody(req);
    return withToken(memberId, token -> webClient.put()
        .uri(BASE + "/calendars/{cal}/events/{eventId}", calendarId, eventId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  /**
   * Partially updates an existing event (PATCH).
   * Only the fields present in {@code fields} are updated.
   */
  public Mono<JsonNode> patchEvent(Long memberId, String calendarId,
                                   String eventId, Map<String, Object> fields) {
    return withToken(memberId, token -> webClient.patch()
        .uri(BASE + "/calendars/{cal}/events/{eventId}", calendarId, eventId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(fields)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  /** Deletes an event permanently. */
  public Mono<Void> deleteEvent(Long memberId, String calendarId, String eventId) {
    return withToken(memberId, token -> webClient.delete()
        .uri(BASE + "/calendars/{cal}/events/{eventId}", calendarId, eventId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(Void.class));
  }

  // ── Event body builder ────────────────────────────────────────────────────

  private Map<String, Object> buildEventBody(CreateEventDTO req) {
    Map<String, Object> body = new LinkedHashMap<>();

    // ─── Time ───────────────────────────────────────────────────────────────
    Map<String, Object> start;
    Map<String, Object> end;

    if (req.isAllDay()) {
      start = Map.of("date", req.getStartDate());
      end   = Map.of("date", req.getEndDate());
    } else {
      start = Map.of("dateTime", req.getStartDateTime(), "timeZone", req.getTimeZone());
      end   = Map.of("dateTime", req.getEndDateTime(),   "timeZone", req.getTimeZone());
    }

    body.put("summary",     req.getSummary());
    body.put("description", req.getDescription());
    body.put("location",    req.getLocation());
    body.put("start",       start);
    body.put("end",         end);

    // ─── Attendees ───────────────────────────────────────────────────────────
    if (req.getAttendees() != null && !req.getAttendees().isEmpty()) {
      body.put("attendees", req.getAttendees().stream()
          .map(email -> Map.of("email", email))
          .toList());
    }

    // ─── Reminders ───────────────────────────────────────────────────────────
    if (req.getReminderMinutes() != null) {
      body.put("reminders", Map.of(
          "useDefault", false,
          "overrides",  List.of(Map.of("method", "popup", "minutes", req.getReminderMinutes()))
      ));
    }

    // ─── Recurrence ──────────────────────────────────────────────────────────
    if (req.getRecurrence() != null && !req.getRecurrence().isEmpty()) {
      body.put("recurrence", req.getRecurrence());
    }

    // ─── Google Meet / conferencing ──────────────────────────────────────────
    if (req.getConferenceSolution() != null) {
      body.put("conferenceData", Map.of(
          "createRequest", Map.of(
              "requestId",             "meet-" + System.currentTimeMillis(),
              "conferenceSolutionKey", Map.of("type", req.getConferenceSolution())
          )
      ));
    }

    // ─── Visibility / status ─────────────────────────────────────────────────
    if (req.getVisibility()   != null) body.put("visibility",   req.getVisibility());
    if (req.getTransparency() != null) body.put("transparency", req.getTransparency());
    if (req.getStatus()       != null) body.put("status",       req.getStatus());

    return body;
  }

  // ── Token helper ──────────────────────────────────────────────────────────

  private <T> Mono<T> withToken(Long memberId, Function<String, Mono<T>> fn) {
    return tokenProvider.apply(memberId).flatMap(fn);
  }
}