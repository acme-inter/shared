package com.acme.shared.payload.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingMsgDTO {
  private Long id;
  private String groupName;
  private String key;
  private String value;
  private Boolean status;
  private Long createdBy;
  private OffsetDateTime updatedAt;
}