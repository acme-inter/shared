package com.acme.shared.payload.file;

import lombok.Data;

import java.util.List;

@Data
public class FileDeleteDTO {
  private List<String> keys;
}
