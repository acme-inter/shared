package com.acme.shared.payload.lark;

import java.util.List;

public final class LarkDto {

  private LarkDto() {}

  /**
   * How the receiver is identified.
   * Use {@code OPEN_ID} for users you know by Lark open_id,
   * {@code CHAT_ID} for groups, {@code EMAIL} for external users.
   */
  public enum ReceiveIdType {
    OPEN_ID, USER_ID, UNION_ID, EMAIL, CHAT_ID;

    public String value() { return name().toLowerCase(); }
  }

  /** Lark message type strings. */
  public enum MsgType {
    TEXT("text"),
    IMAGE("image"),
    FILE("file"),
    AUDIO("audio"),
    MEDIA("media"),
    STICKER("sticker"),
    INTERACTIVE("interactive");   // card

    private final String value;
    MsgType(String v) { this.value = v; }
    public String value() { return value; }
  }

  /**
   * Send any message type to a single user.
   *
   * @param receiveIdType how {@code receiveId} is interpreted
   * @param receiveId     user identifier (open_id / user_id / email / union_id)
   * @param msgType       message type string (text, image, file, interactive…)
   * @param content       JSON content string (use {@link LarkContent} builders)
   */
  public record SendToUserRequest(
      ReceiveIdType receiveIdType,
      String        receiveId,
      String        msgType,
      String        content
  ) {}

  /**
   * Send any message type to a group chat.
   *
   * @param chatId  Lark chat_id of the group
   * @param msgType message type string
   * @param content JSON content string
   */
  public record SendToGroupRequest(
      String chatId,
      String msgType,
      String content
  ) {}

  /**
   * Upload + send a file/image to a user in one call.
   *
   * @param receiveIdType how {@code receiveId} is interpreted
   * @param receiveId     user identifier
   * @param fileName      original file name (used to detect image vs file)
   * @param fileBytes     raw file bytes
   */
  public record SendFileToUserRequest(
      ReceiveIdType receiveIdType,
      String        receiveId,
      String        fileName,
      byte[]        fileBytes
  ) {}

  /**
   * Upload + send a file/image to a group in one call.
   *
   * @param chatId    Lark chat_id of the group
   * @param fileName  original file name
   * @param fileBytes raw file bytes
   */
  public record SendFileToGroupRequest(
      String chatId,
      String fileName,
      byte[] fileBytes
  ) {}

  /**
   * Send a text message to a group and @mention specific users.
   *
   * @param chatId         target group chat_id
   * @param textMessage    message body
   * @param mentionOpenIds list of open_ids to @mention
   */
  public record MentionUsersRequest(
      String       chatId,
      String       textMessage,
      List<String> mentionOpenIds
  ) {}

  /**
   * Send an interactive card message.
   *
   * @param receiveIdType how {@code receiveId} is interpreted
   * @param receiveId     user or chat identifier
   * @param cardJson      full Lark card JSON string
   *                      (build at <a href="https://open.feishu.cn/tool/cardbuilder">Card Builder</a>)
   */
  public record SendCardRequest(
      ReceiveIdType receiveIdType,
      String        receiveId,
      String        cardJson
  ) {}

  /**
   * Create a P2P chat between two users and send a message.
   *
   * @param senderOpenId   open_id of the bot/sender
   * @param receiverOpenId open_id of the recipient
   * @param msgType        message type
   * @param content        JSON content string
   */
  public record P2PSendRequest(
      String senderOpenId,
      String receiverOpenId,
      String msgType,
      String content
  ) {}

  /**
   * Trigger an urgent buzz notification for an already-sent message.
   *
   * @param messageId  the Lark message_id to buzz
   * @param userIdType how {@code userIds} are interpreted (open_id / user_id / union_id)
   * @param userIds    list of users to buzz
   */
  public record BuzzRequest(
      String       messageId,
      String       userIdType,
      List<String> userIds
  ) {}

  /**
   * Internal upload request (image or file).
   *
   * @param fileType  {@code "image"} or {@code "file"}
   * @param fileName  original file name
   * @param fileBytes raw bytes
   */
  public record UploadFileRequest(
      String fileType,
      String fileName,
      byte[] fileBytes
  ) {}

  /**
   * Standard message send response from Lark.
   *
   * @param messageId  Lark-assigned message_id
   * @param chatId     chat the message was sent to
   * @param createTime Unix timestamp (ms) of creation
   * @param msgType    message type echoed back
   */
  public record MessageResponse(
      String messageId,
      String chatId,
      String createTime,
      String msgType
  ) {}

  /**
   * P2P send response — includes the chat_id of the created P2P chat.
   *
   * @param chatId     the P2P chat_id (useful for future sends)
   * @param messageId  Lark-assigned message_id
   * @param createTime Unix timestamp (ms) of creation
   * @param msgType    message type echoed back
   */
  public record P2PResponse(
      String chatId,
      String messageId,
      String createTime,
      String msgType
  ) {}

  /**
   * Buzz response — lists any user IDs that were invalid / unreachable.
   *
   * @param invalidUserIds user IDs that could not be buzzed
   */
  public record BuzzResponse(
      List<String> invalidUserIds
  ) {}

  /**
   * Upload response — the file_key or image_key returned by Lark.
   *
   * @param fileKey  key to reference the uploaded asset in subsequent messages
   * @param fileType {@code "image"} or {@code "file"}
   */
  public record UploadResponse(
      String fileKey,
      String fileType
  ) {}
}