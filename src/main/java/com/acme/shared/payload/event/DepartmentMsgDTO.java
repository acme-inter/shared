package com.acme.shared.payload.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DepartmentMsgDTO {
  private Long id;
  private String code;
  private String name;
  private String color;
  private Long managerId;
  private Boolean isDeleted;
  private Instant updatedAt;
}