package com.acme.shared.payload.file;

import lombok.Data;

import java.util.List;

@Data
public class CopyMoveDTO {
  private List<String> sourceKeys;
  private String destinationPrefix;
}
