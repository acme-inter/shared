package com.acme.shared.payload.gmail;

import lombok.Data;

@Data
public class SendEmailDTO {
  private String  to;
  private String  cc;
  private String  bcc;
  private String  subject;
  private String  body;
  private boolean html;
}
