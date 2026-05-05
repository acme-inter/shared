package com.acme.shared.payload.gmail;

import lombok.Data;

@Data
public class ReplyEmailDTO {
  private String  to;
  private String  cc;
  private String  bcc;
  private String  body;
  private boolean html;
}
