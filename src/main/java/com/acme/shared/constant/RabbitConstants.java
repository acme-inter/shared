package com.acme.shared.constant;

public final class RabbitConstants {

  private RabbitConstants() {}

  // ─── Member ───────────────────────────────────────────────────────────────
  public static final String MEMBER_EXCHANGE    = "member.exchange";
  public static final String MEMBER_QUEUE       = "member.queue";
  public static final String MEMBER_ROUTING_KEY = "member.#";
  public static final String MEMBER_SYNC_ROUTING_KEY = "member.sync";   // ← add

  // ─── Department ───────────────────────────────────────────────────────────
  public static final String DEPARTMENT_EXCHANGE    = "department.exchange";
  public static final String DEPARTMENT_QUEUE       = "department.queue";
  public static final String DEPARTMENT_ROUTING_KEY = "department.#";
  public static final String DEPARTMENT_SYNC_ROUTING_KEY = "department.sync";

  // ─── Setting ──────────────────────────────────────────────────────────────
  public static final String SETTING_EXCHANGE    = "setting.exchange";
  public static final String SETTING_QUEUE       = "setting.queue";
  public static final String SETTING_ROUTING_KEY = "setting.#";

  // ─── Log ──────────────────────────────────────────────────────────────────
  public static final String LOG_EXCHANGE    = "log.exchange";
  public static final String LOG_QUEUE       = "log.queue";
  public static final String LOG_ROUTING_KEY = "log.#";

  // ─── Notification ─────────────────────────────────────────────────────────
  public static final String NOTIFICATION_EXCHANGE    = "notification.exchange";
  public static final String NOTIFICATION_QUEUE       = "notification.queue";
  public static final String NOTIFICATION_ROUTING_KEY = "notification.#";

  // ─── Activity ─────────────────────────────────────────────────────────────
  public static final String ACTIVITY_EXCHANGE    = "activity.exchange";
  public static final String ACTIVITY_QUEUE       = "activity.queue";
  public static final String ACTIVITY_ROUTING_KEY = "activity.#";

  // ─── Sync Request ─────────────────────────────────────────────────────────
  public static final String SYNC_REQUEST_EXCHANGE    = "sync.request.exchange";
  public static final String SYNC_REQUEST_QUEUE       = "sync.request.queue";
  public static final String SYNC_REQUEST_ROUTING_KEY = "sync.request";
  public static final String USER_SYNC_REQUEST_QUEUE  = "user.sync.request.queue";
  public static final String DEPARTMENT_SYNC_REQUEST_QUEUE  = "department.sync.request.queue";
}