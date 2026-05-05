package com.acme.shared.payload.file;

import lombok.Data;

@Data
public class CreateFolderDTO {
  private String prefix = "";
  private String folderName;
}
