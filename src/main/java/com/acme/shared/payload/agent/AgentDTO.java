package com.acme.shared.payload.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDTO {
  private String ip;
  private String browser;
  private String os;
  private String deviceType;
  private String deviceBrand;
  private boolean isBot;
  private boolean isSuspicious;
}