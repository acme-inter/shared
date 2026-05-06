package com.acme.shared.payload.event;

import com.acme.shared.enums.Modules;
import com.acme.shared.payload.agent.AgentDTO;
import com.acme.shared.payload.audit.AuditDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogMsgDTO {
  private Modules module;
  private String action;
  private String clazz;
  private String description;

  // Agent / request info (from AuditDTO / AgentDTO)
  private String ipAddress;
  private String browser;
  private String deviceType;

  // Who did it
  private Long memberId;
  private Long departmentId;

  // What was affected
  private Long ownerId;
  private Long recordId;
  private String oldValue;   // JSON before (null for CREATE)
  private String newValue;   // JSON after  (null for DELETE)

  private Boolean isCollaborate;
  private List<Long> collaborators;

  private OffsetDateTime createdAt;

  public static LogMsgDTO from(
      AuditDTO audit,
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      String oldValue,
      String newValue
  ) {
    return LogMsgDTO.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(audit.getMemberId())
        .departmentId(audit.getDepartmentId())
        .ipAddress(audit.getIp())
        .browser(audit.getBrowser())
        .deviceType(audit.getDeviceType())
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(oldValue)
        .newValue(newValue)
        .createdAt(OffsetDateTime.now())
        .build();
  }

  public static LogMsgDTO from(
      AgentDTO agentDTO,
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      Long memberId,
      Long departmentId
  ) {
    return LogMsgDTO.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(memberId)
        .departmentId(departmentId)
        .ipAddress(agentDTO.getIp())
        .browser(agentDTO.getBrowser())
        .deviceType(agentDTO.getDeviceType())
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(null)
        .newValue(null)
        .createdAt(OffsetDateTime.now())
        .build();
  }

  public static LogMsgDTO from(
      AuditDTO audit,
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      String oldValue,
      String newValue,
      Boolean isCollaborate,
      List<Long> collaborators
  ) {
    return LogMsgDTO.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(audit.getMemberId())
        .departmentId(audit.getDepartmentId())
        .ipAddress(audit.getIp())
        .browser(audit.getBrowser())
        .deviceType(audit.getDeviceType())
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(oldValue)
        .newValue(newValue)
        .isCollaborate(isCollaborate)
        .collaborators(collaborators)
        .createdAt(OffsetDateTime.now())
        .build();
  }
}
