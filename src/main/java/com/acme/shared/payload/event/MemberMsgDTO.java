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
public class MemberMsgDTO {

  private Long id;
  private String username;
  private String firstName;
  private String lastName;
  private String email;
  private String employeeId;
  private String larkId;
  private Long managerId;
  private String avatar;
  private Boolean isActive;
  private Boolean isDeleted;
  private Instant updatedAt;
}