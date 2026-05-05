package com.acme.shared.payload.oauth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GmailStatusDTO {
  private Boolean isGoogleConnect;
  private String  scope;
}
