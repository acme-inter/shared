package com.acme.shared.payload.gmail;

import lombok.Data;

import java.util.List;

@Data
public class BatchDeleteDTO {
  private List<String> ids;
}
