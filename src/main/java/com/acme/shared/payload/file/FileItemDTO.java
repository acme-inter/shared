package com.acme.shared.payload.file;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileItemDTO {
  private String key;
  private String name;
  private String type;       // "file" | "folder"
  private Long size;
  private OffsetDateTime lastModified;
  private String contentType;
  private String path;
  private String etag;
  private String refId;

  public static FileItemDTO folder(String prefix, String parentPrefix) {
    String name = prefix.equals(parentPrefix) ? prefix
        : prefix.substring(parentPrefix.length()).replaceAll("/$", "");
    return FileItemDTO.builder()
        .key(prefix)
        .name(name)
        .type("folder")
        .size(0L)
        .path(parentPrefix)
        .build();
  }

  public static FileItemDTO file(S3Object obj, String parentPrefix) {
    String key = obj.key();
    String name = key.substring(parentPrefix.length());
    return FileItemDTO.builder()
        .key(key)
        .name(name)
        .type("file")
        .size(obj.size())
        .lastModified(obj.lastModified().atOffset(ZoneOffset.UTC))
        .contentType(null)
        .path(parentPrefix)
        .etag(obj.eTag())
        .build();
  }
}