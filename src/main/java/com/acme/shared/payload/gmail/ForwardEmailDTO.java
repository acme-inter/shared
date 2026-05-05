package com.acme.shared.payload.gmail;

import lombok.Data;

@Data
public class ForwardEmailDTO {
  private String to;
  private String note;
}
