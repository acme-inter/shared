package com.acme.shared.payload.file;

import lombok.Data;

import java.util.List;

@Data
public class FileListDTO {
  private List<FileItemDTO> items;
  private int totalItems;
  private int page;
  private int size;
  private int totalPages;
  private String currentPath;
}
