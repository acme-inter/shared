package com.acme.shared.payload.lark;

import java.util.List;

public final class LarkContent {

  private LarkContent() {
    throw new UnsupportedOperationException("Lark content is a static utility class.");
  }

  /**
   * Plain text message.
   *
   * @param text the message body
   */
  public static String text(String text) {
    return "{\"text\":\"" + escape(text) + "\"}";
  }

  /**
   * Text message that @mentions specific users by open_id.
   *
   * <p>Each mentioned user receives an in-app notification. The mention tag
   * is automatically appended after the message text.
   *
   * @param text        message body
   * @param mentionOpenIds list of Lark open_ids to @mention
   */
  public static String textWithMentions(String text, List<String> mentionOpenIds) {
    StringBuilder sb = new StringBuilder("{\"text\":\"").append(escape(text));
    if (mentionOpenIds != null) {
      for (String openId : mentionOpenIds) {
        sb.append(" <at user_id=\\\"").append(openId).append("\\\"></at>");
      }
    }
    sb.append("\"}");
    return sb.toString();
  }

  /**
   * Text message that @mentions everyone in the group ({@code <at user_id="all">}).
   *
   * @param text message body
   */
  public static String textMentionAll(String text) {
    return "{\"text\":\"" + escape(text) + " <at user_id=\\\"all\\\"></at>\"}";
  }

  /**
   * Image message using an already-uploaded image key.
   *
   * @param imageKey the {@code img_key} returned by the Lark image upload API
   */
  public static String image(String imageKey) {
    return "{\"image_key\":\"" + imageKey + "\"}";
  }

  /**
   * File message using an already-uploaded file key.
   *
   * @param fileKey the {@code file_key} returned by the Lark file upload API
   */
  public static String file(String fileKey) {
    return "{\"file_key\":\"" + fileKey + "\"}";
  }

  /**
   * Audio message using an already-uploaded file key.
   *
   * @param fileKey the {@code file_key} of the uploaded audio
   * @param duration duration in milliseconds
   */
  public static String audio(String fileKey, int duration) {
    return "{\"file_key\":\"" + fileKey + "\",\"duration\":" + duration + "}";
  }

  /**
   * Video/media message using an already-uploaded file key.
   *
   * @param fileKey  the {@code file_key} of the uploaded video
   * @param imageKey thumbnail image key (optional — pass {@code null} to omit)
   */
  public static String media(String fileKey, String imageKey) {
    if (imageKey != null && !imageKey.isBlank()) {
      return "{\"file_key\":\"" + fileKey + "\",\"image_key\":\"" + imageKey + "\"}";
    }
    return "{\"file_key\":\"" + fileKey + "\"}";
  }

  /**
   * Sticker message using an already-uploaded sticker key.
   *
   * @param fileKey the {@code file_key} of the sticker
   */
  public static String sticker(String fileKey) {
    return "{\"file_key\":\"" + fileKey + "\"}";
  }

  /**
   * Interactive card message — passes the raw card JSON directly to the Lark API.
   *
   * <p>Build your card at
   * <a href="https://open.feishu.cn/tool/cardbuilder">Lark Card Builder</a>,
   * copy the JSON, and pass it here. The JSON is sent as-is — no wrapping needed.
   *
   * <p>Example minimal card JSON:
   * <pre>
   * {
   *   "elements": [
   *     { "tag": "div", "text": { "content": "Hello **world**", "tag": "lark_md" } }
   *   ],
   *   "header": {
   *     "title": { "content": "My Card", "tag": "plain_text" }
   *   }
   * }
   * </pre>
   *
   * @param cardJson the complete Lark card JSON string
   */
  public static String card(String cardJson) {
    // Cards are sent as-is — the Lark API expects the raw card object in content
    return cardJson;
  }

  /**
   * Escapes a string for safe embedding inside a JSON string value.
   * Handles backslashes, double quotes, newlines, carriage returns, and tabs.
   */
  static String escape(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
