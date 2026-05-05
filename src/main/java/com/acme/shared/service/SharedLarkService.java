package com.acme.shared.service;

import com.acme.shared.exception.LarkException;
import com.acme.shared.payload.lark.LarkContent;
import com.acme.shared.payload.lark.LarkDto;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.enums.CreateMessageReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SharedLarkService {

  private final Client client;

  public Mono<LarkDto.MessageResponse> botToUser(LarkDto.ReceiveIdType receiveIdType,
                                                 String receiveId,
                                                 String msgType,
                                                 String content) {
    return send(receiveIdType, receiveId, msgType, content);
  }

  /**
   * Bot sends a plain text message to a user.
   *
   * @param receiveIdType how {@code receiveId} is interpreted
   * @param receiveId     user identifier
   * @param text          plain text body
   */
  public Mono<LarkDto.MessageResponse> botTextToUser(LarkDto.ReceiveIdType receiveIdType,
                                                     String receiveId,
                                                     String text) {
    return send(receiveIdType, receiveId,
        LarkDto.MsgType.TEXT.value(), LarkContent.text(text));
  }

  /**
   * Bot sends an interactive card to a user.
   *
   * @param receiveIdType how {@code receiveId} is interpreted
   * @param receiveId     user identifier
   * @param cardJson      full Lark card JSON string from Card Builder
   */
  public Mono<LarkDto.MessageResponse> cardToUser(LarkDto.ReceiveIdType receiveIdType,
                                                  String receiveId,
                                                  String cardJson) {
    return send(receiveIdType, receiveId,
        LarkDto.MsgType.INTERACTIVE.value(), LarkContent.card(cardJson));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // BOT → GROUP
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Send any message from the bot to a group chat.
   *
   * @param chatId  Lark chat_id of the group
   * @param msgType message type string
   * @param content JSON content — use {@link LarkContent} builders
   */
  public Mono<LarkDto.MessageResponse> botToGroup(String chatId,
                                                  String msgType,
                                                  String content) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId, msgType, content);
  }

  /**
   * Bot sends a plain text message to a group.
   *
   * @param chatId Lark chat_id of the group
   * @param text   plain text body
   */
  public Mono<LarkDto.MessageResponse> botTextToGroup(String chatId, String text) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.TEXT.value(), LarkContent.text(text));
  }

  /**
   * Bot sends an interactive card to a group.
   *
   * @param chatId   Lark chat_id of the group
   * @param cardJson full Lark card JSON string from Card Builder
   */
  public Mono<LarkDto.MessageResponse> cardToGroup(String chatId, String cardJson) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.INTERACTIVE.value(), LarkContent.card(cardJson));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // USER → USER  (P2P chat)
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Creates a P2P chat between two users and sends a message.
   * If the P2P chat already exists Lark returns the existing one.
   *
   * @param req sender + receiver open_ids, msgType, content
   */
  public Mono<LarkDto.P2PResponse> userToUser(LarkDto.P2PSendRequest req) {
    return Mono.fromCallable(() -> {
          log.info("[Lark/P2P] sender={} receiver={} type={}",
              req.senderOpenId(), req.receiverOpenId(), req.msgType());

          // Step 1 — get or create the P2P chat
          CreateChatResp chatResp = client.im().chat().create(
              CreateChatReq.newBuilder()
                  .createChatReqBody(CreateChatReqBody.newBuilder()
                      .chatType("p2p")
                      .userIdList(new String[]{req.senderOpenId(), req.receiverOpenId()})
                      .build())
                  .build());

          if (chatResp.getCode() != 0) {
            throw new LarkException(chatResp.getCode(),
                "P2P chat create failed: " + chatResp.getMsg());
          }
          String chatId = chatResp.getData().getChatId();
          CreateMessageResp msgResp = client.im().message().create(
              CreateMessageReq.newBuilder()
                  .receiveIdType(CreateMessageReceiveIdTypeEnum.CHAT_ID)
                  .createMessageReqBody(CreateMessageReqBody.newBuilder()
                      .receiveId(chatId)
                      .msgType(req.msgType())
                      .content(req.content())
                      .build())
                  .build());

          if (msgResp.getCode() != 0) {
            throw new LarkException(msgResp.getCode(),
                "P2P message send failed: " + msgResp.getMsg());
          }

          var d = msgResp.getData();
          return new LarkDto.P2PResponse(chatId, d.getMessageId(),
              d.getCreateTime(), d.getMsgType());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark/P2P] failed: {}", e.getMessage()));
  }

  /**
   * Convenience — user sends plain text to another user (P2P).
   */
  public Mono<LarkDto.P2PResponse> userTextToUser(String senderOpenId,
                                                  String receiverOpenId,
                                                  String text) {
    return userToUser(new LarkDto.P2PSendRequest(
        senderOpenId, receiverOpenId,
        LarkDto.MsgType.TEXT.value(), LarkContent.text(text)));
  }

  /**
   * User sends an interactive card to another user (P2P).
   */
  public Mono<LarkDto.P2PResponse> userCardToUser(String senderOpenId,
                                                  String receiverOpenId,
                                                  String cardJson) {
    return userToUser(new LarkDto.P2PSendRequest(
        senderOpenId, receiverOpenId,
        LarkDto.MsgType.INTERACTIVE.value(), LarkContent.card(cardJson)));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // USER → GROUP
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Send any message from a user into a group chat.
   * Uses {@code chat_id} as the ID type.
   *
   * @param chatId  Lark chat_id of the group
   * @param msgType message type string
   * @param content JSON content — use {@link LarkContent} builders
   */
  public Mono<LarkDto.MessageResponse> userToGroup(String chatId,
                                                   String msgType,
                                                   String content) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId, msgType, content);
  }

  /**
   * User sends plain text to a group.
   */
  public Mono<LarkDto.MessageResponse> userTextToGroup(String chatId, String text) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.TEXT.value(), LarkContent.text(text));
  }

  /**
   * User sends an interactive card to a group.
   */
  public Mono<LarkDto.MessageResponse> userCardToGroup(String chatId, String cardJson) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.INTERACTIVE.value(), LarkContent.card(cardJson));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // @MENTION
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Post a text message to a group and @mention specific users.
   *
   * @param chatId         target group chat_id
   * @param text           message body
   * @param mentionOpenIds list of open_ids to @mention
   */
  public Mono<LarkDto.MessageResponse> mentionUsers(String chatId,
                                                    String text,
                                                    List<String> mentionOpenIds) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.TEXT.value(),
        LarkContent.textWithMentions(text, mentionOpenIds));
  }

  /**
   * Post a text message to a group and @mention everyone.
   *
   * @param chatId target group chat_id
   * @param text   message body
   */
  public Mono<LarkDto.MessageResponse> mentionAll(String chatId, String text) {
    return send(LarkDto.ReceiveIdType.CHAT_ID, chatId,
        LarkDto.MsgType.TEXT.value(),
        LarkContent.textMentionAll(text));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // FILE / IMAGE
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Upload a file or image and send it to a user.
   *
   * @param req receiveIdType, receiveId, fileName, fileBytes
   */
  public Mono<LarkDto.MessageResponse> sendFileToUser(LarkDto.SendFileToUserRequest req) {
    return uploadFile(req.fileName(), req.fileBytes())
        .flatMap(upload -> {
          String content = buildFileContent(upload.fileType(), upload.fileKey());
          String msgType = "image".equals(upload.fileType())
              ? LarkDto.MsgType.IMAGE.value()
              : LarkDto.MsgType.FILE.value();
          return send(req.receiveIdType(), req.receiveId(), msgType, content);
        });
  }

  /**
   * Upload a file or image and send it to a group.
   *
   * @param req chatId, fileName, fileBytes
   */
  public Mono<LarkDto.MessageResponse> sendFileToGroup(LarkDto.SendFileToGroupRequest req) {
    return uploadFile(req.fileName(), req.fileBytes())
        .flatMap(upload -> {
          String content = buildFileContent(upload.fileType(), upload.fileKey());
          String msgType = "image".equals(upload.fileType())
              ? LarkDto.MsgType.IMAGE.value()
              : LarkDto.MsgType.FILE.value();
          return send(LarkDto.ReceiveIdType.CHAT_ID, req.chatId(), msgType, content);
        });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // REPLY
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Reply to an existing message by its message_id.
   *
   * @param messageId Lark message_id of the original message
   * @param msgType   reply message type
   * @param content   JSON content — use {@link LarkContent} builders
   */
  public Mono<LarkDto.MessageResponse> reply(String messageId,
                                             String msgType,
                                             String content) {
    return Mono.fromCallable(() -> {
          log.info("[Lark] reply messageId={} type={}", messageId, msgType);

          ReplyMessageResp resp = client.im().message().reply(
              ReplyMessageReq.newBuilder()
                  .messageId(messageId)
                  .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                      .msgType(msgType)
                      .content(content)
                      .build())
                  .build());

          if (resp.getCode() != 0) {
            throw new LarkException(resp.getCode(), resp.getMsg());
          }
          var d = resp.getData();
          return new LarkDto.MessageResponse(
              d.getMessageId(), d.getChatId(), d.getCreateTime(), d.getMsgType());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark] reply failed: {}", e.getMessage()));
  }

  /**
   * Reply with a plain text message.
   */
  public Mono<LarkDto.MessageResponse> replyText(String messageId, String text) {
    return reply(messageId, LarkDto.MsgType.TEXT.value(), LarkContent.text(text));
  }

  /**
   * Reply with an interactive card.
   */
  public Mono<LarkDto.MessageResponse> replyCard(String messageId, String cardJson) {
    return reply(messageId, LarkDto.MsgType.INTERACTIVE.value(), LarkContent.card(cardJson));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // BUZZ — in-app (web + mobile notification banner)
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Trigger an in-app urgent buzz notification (banner + vibration on mobile,
   * notification banner on web) for an already-sent message.
   *
   * <p>Lark API: {@code PATCH /open-apis/im/v1/messages/{message_id}/urgent_app}
   *
   * @param req messageId, userIdType, list of userIds to buzz
   */
  public Mono<LarkDto.BuzzResponse> buzzApp(LarkDto.BuzzRequest req) {
    return Mono.fromCallable(() -> {
          log.info("[Lark/Buzz-App] messageId={} users={}", req.messageId(), req.userIds());

          UrgentAppMessageResp resp = client.im().message().urgentApp(
              UrgentAppMessageReq.newBuilder()
                  .messageId(req.messageId())
                  .userIdType(req.userIdType())
                  .urgentReceivers(UrgentReceivers.newBuilder()
                      .userIdList(req.userIds().toArray(new String[0]))
                      .build())
                  .build());

          if (resp.getCode() != 0) {
            throw new LarkException(resp.getCode(), "Buzz app failed: " + resp.getMsg());
          }

          List<String> invalid = (resp.getData() != null
              && resp.getData().getInvalidUserIdList() != null)
              ? Arrays.asList(resp.getData().getInvalidUserIdList())
              : Collections.emptyList();

          return new LarkDto.BuzzResponse(invalid);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark/Buzz-App] failed: {}", e.getMessage()));
  }

  /**
   * Convenience — buzz a single user via app notification.
   *
   * @param messageId  message to buzz
   * @param userIdType how {@code userId} is interpreted
   * @param userId     the user to buzz
   */
  public Mono<LarkDto.BuzzResponse> buzzAppUser(String messageId,
                                                String userIdType,
                                                String userId) {
    return buzzApp(new LarkDto.BuzzRequest(messageId, userIdType, List.of(userId)));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // BUZZ — phone call
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Trigger a phone-call urgent buzz notification for an already-sent message.
   *
   * <p>Lark API: {@code PATCH /open-apis/im/v1/messages/{message_id}/urgent_phone}
   *
   * @param req messageId, userIdType, list of userIds to call
   */
  public Mono<LarkDto.BuzzResponse> buzzPhone(LarkDto.BuzzRequest req) {
    return Mono.fromCallable(() -> {
          log.info("[Lark/Buzz-Phone] messageId={} users={}", req.messageId(), req.userIds());

          UrgentPhoneMessageResp resp = client.im().message().urgentPhone(
              UrgentPhoneMessageReq.newBuilder()
                  .messageId(req.messageId())
                  .userIdType(req.userIdType())
                  .urgentReceivers(UrgentReceivers.newBuilder()
                      .userIdList(req.userIds().toArray(new String[0]))
                      .build())
                  .build());

          if (resp.getCode() != 0) {
            throw new LarkException(resp.getCode(), "Buzz phone failed: " + resp.getMsg());
          }

          List<String> invalid = (resp.getData() != null
              && resp.getData().getInvalidUserIdList() != null)
              ? Arrays.asList(resp.getData().getInvalidUserIdList())
              : Collections.emptyList();

          return new LarkDto.BuzzResponse(invalid);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark/Buzz-Phone] failed: {}", e.getMessage()));
  }

  /**
   * Convenience — phone-buzz a single user.
   *
   * @param messageId  message to buzz
   * @param userIdType how {@code userId} is interpreted
   * @param userId     the user to call
   */
  public Mono<LarkDto.BuzzResponse> buzzPhoneUser(String messageId,
                                                  String userIdType,
                                                  String userId) {
    return buzzPhone(new LarkDto.BuzzRequest(messageId, userIdType, List.of(userId)));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PRIVATE — core send + upload helpers
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Core send — all public send methods delegate here.
   */
  private Mono<LarkDto.MessageResponse> send(LarkDto.ReceiveIdType receiveIdType,
                                             String receiveId,
                                             String msgType,
                                             String content) {
    return Mono.fromCallable(() -> {
          log.info("[Lark] send type={} id={} msgType={}", receiveIdType, receiveId, msgType);

          CreateMessageResp resp = client.im().message().create(
              CreateMessageReq.newBuilder()
                  .receiveIdType(resolveReceiveIdType(receiveIdType))
                  .createMessageReqBody(CreateMessageReqBody.newBuilder()
                      .receiveId(receiveId)
                      .msgType(msgType)
                      .content(content)
                      .build())
                  .build());

          if (resp.getCode() != 0) {
            throw new LarkException(resp.getCode(), resp.getMsg());
          }
          var d = resp.getData();
          return new LarkDto.MessageResponse(
              d.getMessageId(), d.getChatId(), d.getCreateTime(), d.getMsgType());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark] send failed: {}", e.getMessage()));
  }

  /**
   * Uploads a file or image to Lark and returns the file_key / image_key.
   * Detects image vs file by file extension automatically.
   */
  private Mono<LarkDto.UploadResponse> uploadFile(String fileName, byte[] fileBytes) {
    return Mono.fromCallable(() -> {
          log.info("[Lark] upload name={} size={}B", fileName, fileBytes.length);
          Path tempPath = Files.createTempFile("lark_", "_" + fileName);
          Files.write(tempPath, fileBytes);

          if (isImage(fileName)) {
            CreateImageResp resp = client.im().image().create(
                CreateImageReq.newBuilder()
                    .createImageReqBody(CreateImageReqBody.newBuilder()
                        .imageType("message")
                        .image(tempPath.toFile())
                        .build())
                    .build());
            if (resp.getCode() != 0) {
              throw new LarkException(resp.getCode(), resp.getMsg());
            }
            return new LarkDto.UploadResponse(resp.getData().getImageKey(), "image");
          } else {
            CreateFileResp resp = client.im().file().create(
                CreateFileReq.newBuilder()
                    .createFileReqBody(CreateFileReqBody.newBuilder()
                        .fileType(resolveFileType(fileName))
                        .fileName(fileName)
                        .file(tempPath.toFile())
                        .build())
                    .build());
            if (resp.getCode() != 0) {
              throw new LarkException(resp.getCode(), resp.getMsg());
            }
            return new LarkDto.UploadResponse(resp.getData().getFileKey(), "file");
          }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[Lark] upload failed: {}", e.getMessage()));
  }

  // ─── Mapping helpers ──────────────────────────────────────────────────────

  private CreateMessageReceiveIdTypeEnum resolveReceiveIdType(LarkDto.ReceiveIdType type) {
    return switch (type) {
      case OPEN_ID  -> CreateMessageReceiveIdTypeEnum.OPEN_ID;
      case USER_ID  -> CreateMessageReceiveIdTypeEnum.USER_ID;
      case UNION_ID -> CreateMessageReceiveIdTypeEnum.UNION_ID;
      case EMAIL    -> CreateMessageReceiveIdTypeEnum.EMAIL;
      case CHAT_ID  -> CreateMessageReceiveIdTypeEnum.CHAT_ID;
    };
  }

  private String buildFileContent(String fileType, String fileKey) {
    return "image".equals(fileType)
        ? LarkContent.image(fileKey)
        : LarkContent.file(fileKey);
  }

  private boolean isImage(String fileName) {
    if (fileName == null) return false;
    String lower = fileName.toLowerCase();
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
        || lower.endsWith(".png") || lower.endsWith(".gif")
        || lower.endsWith(".webp");
  }

  private String resolveFileType(String fileName) {
    if (fileName == null) return "stream";
    String lower = fileName.toLowerCase();
    if (lower.endsWith(".pdf"))                            return "pdf";
    if (lower.endsWith(".doc")  || lower.endsWith(".docx")) return "doc";
    if (lower.endsWith(".xls")  || lower.endsWith(".xlsx")) return "xls";
    if (lower.endsWith(".ppt")  || lower.endsWith(".pptx")) return "ppt";
    if (lower.endsWith(".txt"))                            return "txt";
    if (lower.endsWith(".csv"))                            return "csv";
    return "stream";
  }
}