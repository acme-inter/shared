package com.acme.shared.service;

import com.acme.shared.payload.gmail.EmailAttachment;
import com.acme.shared.payload.gmail.ForwardEmailDTO;
import com.acme.shared.payload.gmail.ReplyEmailDTO;
import com.acme.shared.payload.gmail.SendEmailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class SharedGmailService {

  private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

  private final WebClient webClient;
  private final Function<Long, Mono<String>> tokenProvider;

  public Mono<JsonNode> listMessages(Long memberId, String labelIds,
                                     String q, int maxResults, String pageToken) {
    StringBuilder uri = new StringBuilder(BASE + "/messages?maxResults=" + maxResults);
    if (labelIds  != null) uri.append("&labelIds=").append(labelIds);
    if (q         != null) uri.append("&q=").append(q);
    if (pageToken != null) uri.append("&pageToken=").append(pageToken);
    String uriStr = uri.toString();

    return withToken(memberId, token -> webClient.get()
        .uri(uriStr)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> getMessage(Long memberId, String messageId, String format) {
    String fmt = format != null ? format : "full";
    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/messages/{id}?format={fmt}", messageId, fmt)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Flux<JsonNode> listWithMeta(Long memberId, String labelIds, String q,
                                     int maxResults, String pageToken) {
    return listMessages(memberId, labelIds, q, maxResults, pageToken)
        .flatMapMany(node -> {
          if (!node.has("messages") || !node.get("messages").isArray()) return Flux.empty();
          List<JsonNode> ids = new ArrayList<>();
          node.get("messages").forEach(ids::add);
          return Flux.fromIterable(ids);
        })
        .flatMap(ref -> getMessage(memberId, ref.get("id").asString(), "metadata"), 8);
  }

  public Mono<JsonNode> sendEmail(Long memberId, SendEmailDTO req,
                                  List<EmailAttachment> attachments) {
    String raw = buildRawMessage(
        req.getTo(), req.getCc(), req.getBcc(),
        req.getSubject(), req.getBody(), req.isHtml(),
        null, null, attachments);

    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/messages/send")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("raw", raw))
        .retrieve()
        .bodyToMono(JsonNode.class));
  }


  public Mono<JsonNode> replyToMessage(Long memberId, String originalMessageId,
                                       ReplyEmailDTO req) {
    return getMessage(memberId, originalMessageId, "metadata")
        .flatMap(original -> {
          String threadId     = original.path("threadId").asString();
          String originalMid  = getHeader(original, "Message-ID");
          String subject      = getHeader(original, "Subject");
          String replySubject = subject.startsWith("Re:") ? subject : "Re: " + subject;

          String raw = buildRawMessage(
              req.getTo(), req.getCc(), req.getBcc(),
              replySubject, req.getBody(), req.isHtml(),
              originalMid, threadId, null);

          return withToken(memberId, token -> webClient.post()
              .uri(BASE + "/messages/send")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(Map.of("raw", raw, "threadId", threadId))
              .retrieve()
              .bodyToMono(JsonNode.class));
        });
  }

  public Mono<JsonNode> forwardMessage(Long memberId, String originalMessageId,
                                       ForwardEmailDTO req) {
    return getMessage(memberId, originalMessageId, "full")
        .flatMap(original -> {
          String subject      = "Fwd: " + getHeader(original, "Subject");
          String originalBody = extractBody(original);
          String forwardedBody = (req.getNote() != null ? req.getNote() : "")
              + "\n\n---------- Forwarded message ----------\n" + originalBody;

          String raw = buildRawMessage(
              req.getTo(), null, null,
              subject, forwardedBody, false,
              null, null, null);

          return withToken(memberId, token -> webClient.post()
              .uri(BASE + "/messages/send")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(Map.of("raw", raw))
              .retrieve()
              .bodyToMono(JsonNode.class));
        });
  }

  public Mono<JsonNode> trashMessage(Long memberId, String messageId) {
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/messages/{id}/trash", messageId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> unTrashMessage(Long memberId, String messageId) {
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/messages/{id}/untrash", messageId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<Void> deleteMessagePermanently(Long memberId, String messageId) {
    return withToken(memberId, token -> webClient.delete()
        .uri(BASE + "/messages/{id}", messageId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(Void.class));
  }

  public Mono<Void> batchTrash(Long memberId, List<String> messageIds) {
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/messages/batchDelete")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("ids", messageIds))
        .retrieve()
        .bodyToMono(Void.class));
  }

  public Mono<JsonNode> modifyLabels(Long memberId, String messageId,
                                     List<String> addLabelIds, List<String> removeLabelIds) {
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/messages/{id}/modify", messageId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "addLabelIds",    addLabelIds    != null ? addLabelIds    : List.of(),
            "removeLabelIds", removeLabelIds != null ? removeLabelIds : List.of()
        ))
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> markAsRead(Long memberId, String messageId) {
    return modifyLabels(memberId, messageId, null, List.of("UNREAD"));
  }

  public Mono<JsonNode> markAsUnread(Long memberId, String messageId) {
    return modifyLabels(memberId, messageId, List.of("UNREAD"), null);
  }

  public Mono<JsonNode> star(Long memberId, String messageId) {
    return modifyLabels(memberId, messageId, List.of("STARRED"), null);
  }

  public Mono<JsonNode> listLabels(Long memberId) {
    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/labels")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> getThread(Long memberId, String threadId) {
    return withToken(memberId, token -> webClient.get()
        .uri(BASE + "/threads/{id}?format=full", threadId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  public Mono<JsonNode> createDraft(Long memberId, SendEmailDTO req) {
    String raw = buildRawMessage(req.getTo(), req.getCc(), req.getBcc(),
        req.getSubject(), req.getBody(), req.isHtml(), null, null, null);
    return withToken(memberId, token -> webClient.post()
        .uri(BASE + "/drafts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("message", Map.of("raw", raw)))
        .retrieve()
        .bodyToMono(JsonNode.class));
  }

  @SuppressWarnings("unused")
  private String buildRawMessage(String to, String cc, String bcc, String subject,
                                 String body, boolean html, String inReplyTo,
                                 String threadId, List<EmailAttachment> attachments) {

    String boundary = "----=_Part_" + System.currentTimeMillis();
    StringBuilder sb = new StringBuilder();

    sb.append("To: ").append(to).append("\r\n");
    if (cc  != null && !cc.isBlank())  sb.append("Cc: ").append(cc).append("\r\n");
    if (bcc != null && !bcc.isBlank()) sb.append("Bcc: ").append(bcc).append("\r\n");
    sb.append("Subject: ").append(subject).append("\r\n");
    sb.append("MIME-Version: 1.0\r\n");
    if (inReplyTo != null && !inReplyTo.isBlank())
      sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");

    if (attachments == null || attachments.isEmpty()) {
      sb.append("Content-Type: ")
          .append(html ? "text/html" : "text/plain")
          .append("; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
      sb.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
    } else {
      sb.append("Content-Type: multipart/mixed; boundary=").append(boundary).append("\r\n\r\n");
      sb.append("--").append(boundary).append("\r\n");
      sb.append("Content-Type: ").append(html ? "text/html" : "text/plain")
          .append("; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
      sb.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");

      for (EmailAttachment att : attachments) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: ").append(att.contentType())
            .append("; name=\"").append(att.filename()).append("\"\r\n");
        sb.append("Content-Disposition: attachment; filename=\"")
            .append(att.filename()).append("\"\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getMimeEncoder().encodeToString(att.data())).append("\r\n");
      }
      sb.append("--").append(boundary).append("--");
    }
    return Base64.getUrlEncoder()
        .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  public String getHeader(JsonNode message, String name) {
    JsonNode headers = message.path("payload").path("headers");
    if (headers.isArray()) {
      for (JsonNode h : headers) {
        if (name.equalsIgnoreCase(h.path("name").asString())) {
          return h.path("value").asString("");
        }
      }
    }
    return "";
  }

  public String extractBody(JsonNode message) {
    JsonNode parts = message.path("payload").path("parts");
    if (parts.isArray()) {
      for (JsonNode part : parts) {
        if ("text/plain".equals(part.path("mimeType").asString())) {
          String encoded = part.path("body").path("data").asString("");
          if (!encoded.isEmpty())
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
      }
    }
    String encoded = message.path("payload").path("body").path("data").asString("");
    if (!encoded.isEmpty())
      return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    return message.path("snippet").asString("");
  }

  private <T> Mono<T> withToken(Long memberId, Function<String, Mono<T>> fn) {
    return tokenProvider.apply(memberId).flatMap(fn);
  }
}