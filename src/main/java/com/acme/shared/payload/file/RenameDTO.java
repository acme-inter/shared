package com.acme.shared.payload.file;

import lombok.Data;

@Data
public class RenameDTO {
  private String oldKey;
  private String newName;
}
