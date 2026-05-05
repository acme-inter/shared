package com.acme.shared.payload.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleResponseDTO {
  @JsonProperty("access_token")  private String accessToken;
  @JsonProperty("refresh_token") private String refreshToken;
  @JsonProperty("expires_in")    private Long expiresIn;
  @JsonProperty("scope")         private String scope;
  @JsonProperty("token_type")    private String tokenType;
}