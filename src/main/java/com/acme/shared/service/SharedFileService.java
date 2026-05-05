package com.acme.shared.service;

import com.acme.shared.exception.ThrowException;
import com.acme.shared.payload.file.FileItemDTO;
import com.acme.shared.payload.file.FileListResponseDTO;
import com.acme.shared.payload.file.StorageStatsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SharedFileService {

  private final S3AsyncClient s3AsyncClient;

  @Value("${hetzner.s3.bucket}")
  private String bucket;

  @Value("${hetzner.s3.endpoint}")
  private String endpoint;

  private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
      "image/jpeg", "image/png", "image/webp"
  );

  public Mono<String> uploadImage(String prefix, FilePart image) {
    if (image == null) return Mono.just("");

    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    String ext        = getExtension(image.filename());
    String newFilename = UUID.randomUUID() + "." + ext;
    FilePart renamed  = renameFilePart(image, newFilename);

    return uploadFile(prefix, renamed).map(FileItemDTO::getKey);
  }

  public Mono<String> uploadImage(String refId, String prefix, FilePart filePart) {

    String datePrefix = LocalDate.now()
        .format(DateTimeFormatter.BASIC_ISO_DATE);

    String contentType = detectContentType(filePart.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
      return Mono.error(new ThrowException("image.invalid.type"));
    }

    String ext = getExtension(filePart.filename());

    String newFilename =
        datePrefix + "-" + refId + "-" + UUID.randomUUID() + "." + ext;

    FilePart renamed = renameFilePart(filePart, newFilename);

    return uploadFile(prefix, renamed)
        .map(FileItemDTO::getKey);
  }

  public Mono<String> uploadAvatar(String prefix, FilePart image) {
    if (image == null) return Mono.just("");

    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    return image.content()
        .reduce(new ByteArrayOutputStream(), (baos, buf) -> {
          byte[] bytes = new byte[buf.readableByteCount()];
          buf.read(bytes);
          baos.write(bytes, 0, bytes.length);
          return baos;
        })
        .flatMap(baos -> Mono.fromCallable(() -> {
          BufferedImage original = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
          if (original == null) throw new ThrowException("image.invalid.format");

          int origW = original.getWidth();
          int origH = original.getHeight();
          double scale = Math.min(80.0 / origW, 80.0 / origH);
          int newW = (int) (origW * scale);
          int newH = (int) (origH * scale);

          BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
          Graphics2D g = resized.createGraphics();
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
          g.drawImage(original, 0, 0, newW, newH, null);
          g.dispose();

          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
          ImageWriteParam param = writer.getDefaultWriteParam();
          param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
          param.setCompressionQuality(0.75f);
          writer.setOutput(ImageIO.createImageOutputStream(out));
          writer.write(null, new IIOImage(resized, null, null), param);
          writer.dispose();

          return out.toByteArray();
        }).subscribeOn(Schedulers.boundedElastic()))
        .flatMap(bytes -> {
          String key = normalizePrefix(prefix) + UUID.randomUUID() + ".jpg";

          PutObjectRequest req = PutObjectRequest.builder()
              .bucket(bucket).key(key)
              .contentType("image/jpeg")
              .contentLength((long) bytes.length)
              .build();

          return Mono.fromFuture(
              s3AsyncClient.putObject(req, AsyncRequestBody.fromBytes(bytes))
          ).map(r -> key);
        });
  }

  public Mono<List<String>> uploadImages(String refId, String prefix, Flux<FilePart> fileParts) {
    String datePrefix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return fileParts
        .flatMap(fp -> {
          String contentType = detectContentType(fp.filename());
          if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return Mono.error(new ThrowException("image.invalid.type"));
          }
          String ext = getExtension(fp.filename());
          String newFilename = datePrefix + "-" + refId + "-" + UUID.randomUUID() + "." + ext;
          FilePart renamed = renameFilePart(fp, newFilename);
          return uploadFile(prefix, renamed)
              .map(FileItemDTO::getKey);
        })
        .collectList();
  }

  public Mono<String> replaceImage(String prefix, FilePart image, String existingKey) {
    if (image == null) return Mono.just(existingKey != null ? existingKey : "");

    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    String ext         = getExtension(image.filename());
    String newFilename = UUID.randomUUID() + "." + ext;
    FilePart renamed   = renameFilePart(image, newFilename);

    Mono<Void> deleteOld = (existingKey != null && !existingKey.isBlank())
        ? delete(List.of(existingKey)).onErrorResume(e -> {
      log.warn("Failed to delete old image: {}", e.getMessage());
      return Mono.empty();
    })
        : Mono.empty();

    return deleteOld
        .then(uploadFile(prefix, renamed))
        .map(FileItemDTO::getKey);
  }

  @NullMarked
  public FilePart renameFilePart(FilePart original, String newFilename) {
    return new FilePart() {
      @Override public String name()                      { return original.name(); }
      @Override public String filename()                  { return newFilename; }
      @Override public HttpHeaders headers()              { return original.headers(); }
      @Override public Flux<DataBuffer> content()        { return original.content(); }
      @Override public Mono<Void> transferTo(Path dest)  { return original.transferTo(dest); }
    };
  }

  // ── upload single ─────────────────────────────────────────
  public Mono<FileItemDTO> uploadFile(String prefix, FilePart filePart) {
    String normalizedPrefix = normalizePrefix(prefix);
    String key              = normalizedPrefix + filePart.filename();
    String contentType      = detectContentType(filePart.filename());

    return filePart.content()
        .reduce(new ByteArrayOutputStream(), (baos, buf) -> {
          byte[] bytes = new byte[buf.readableByteCount()];
          buf.read(bytes);
          baos.write(bytes, 0, bytes.length);
          return baos;
        })
        .flatMap(baos -> {
          byte[] bytes = baos.toByteArray();

          PutObjectRequest req = PutObjectRequest.builder()
              .bucket(bucket).key(key)
              .contentType(contentType)
              .contentLength((long) bytes.length)
              .build();

          return Mono.fromFuture(s3AsyncClient.putObject(req, AsyncRequestBody.fromBytes(bytes)))
              .flatMap(r -> Mono.fromFuture(s3AsyncClient.headObject(
                  HeadObjectRequest.builder().bucket(bucket).key(key).build())))
              .map(head -> FileItemDTO.builder()
                  .key(key).name(filePart.filename()).type("file")
                  .size(head.contentLength()).lastModified(head.lastModified().atOffset(ZoneOffset.UTC))
                  .contentType(contentType).path(normalizedPrefix)
                  .build());
        });
  }

  // ── upload multiple ───────────────────────────────────────
  public Flux<FileItemDTO> uploadFiles(String prefix, Flux<FilePart> fileParts) {
    return fileParts.flatMap(fp -> uploadFile(prefix, fp));
  }

  // ── create folder ─────────────────────────────────────────
  public Mono<FileItemDTO> createFolder(String prefix, String folderName) {
    String normalizedPrefix = normalizePrefix(prefix);
    String folderKey        = normalizedPrefix + sanitizeName(folderName) + "/";

    return Mono.fromFuture(s3AsyncClient.putObject(
            PutObjectRequest.builder()
                .bucket(bucket).key(folderKey).contentLength(0L).build(),
            AsyncRequestBody.fromBytes(new byte[0])))
        .thenReturn(FileItemDTO.builder()
            .key(folderKey).name(folderName).type("folder")
            .size(0L).lastModified(OffsetDateTime.now()).path(normalizedPrefix)
            .build());
  }

  // ── rename ────────────────────────────────────────────────
  public Mono<FileItemDTO> rename(String oldKey, String newName) {
    boolean isFolder  = oldKey.endsWith("/");
    String  parentPath = getParentPath(oldKey);
    String  newKey    = parentPath + sanitizeName(newName) + (isFolder ? "/" : "");

    Mono<Void> copyOp   = isFolder ? copyFolderReactive(oldKey, newKey)   : copyObjectReactive(oldKey, newKey);
    Mono<Void> deleteOp = isFolder ? deleteFolderReactive(oldKey)         : deleteObjectReactive(oldKey);

    return copyOp.then(deleteOp)
        .thenReturn(FileItemDTO.builder()
            .key(newKey).name(newName)
            .type(isFolder ? "folder" : "file")
            .path(parentPath).lastModified(OffsetDateTime.now())
            .build());
  }

  // ── copy ──────────────────────────────────────────────────
  public Mono<List<FileItemDTO>> copy(List<String> sourceKeys, String destinationPrefix) {
    String destPrefix = normalizePrefix(destinationPrefix);
    return Flux.fromIterable(sourceKeys)
        .flatMap(sourceKey -> {
          boolean isFolder = sourceKey.endsWith("/");
          String  name     = getItemName(sourceKey);
          String  destKey  = destPrefix + name + (isFolder ? "/" : "");
          Mono<Void> op    = isFolder ? copyFolderReactive(sourceKey, destKey) : copyObjectReactive(sourceKey, destKey);
          return op.thenReturn(FileItemDTO.builder()
              .key(destKey).name(name)
              .type(isFolder ? "folder" : "file")
              .path(destPrefix).lastModified(OffsetDateTime.now())
              .build());
        })
        .collectList();
  }

  // ── move ──────────────────────────────────────────────────
  public Mono<List<FileItemDTO>> move(List<String> sourceKeys, String destinationPrefix) {
    return copy(sourceKeys, destinationPrefix)
        .flatMap(copied ->
            Flux.fromIterable(sourceKeys)
                .flatMap(key -> key.endsWith("/") ? deleteFolderReactive(key) : deleteObjectReactive(key))
                .then()
                .thenReturn(copied));
  }

  // ── delete ────────────────────────────────────────────────
  public Mono<Void> delete(List<String> keys) {
    if (keys == null || keys.isEmpty()) return Mono.empty();
    return Flux.fromIterable(keys)
        .flatMap(key -> key.endsWith("/") ? deleteFolderReactive(key) : deleteObjectReactive(key))
        .then();
  }

  // ── list directory ────────────────────────────────────────
  public Mono<FileListResponseDTO> listDirectory(String prefix, int page, int size,
                                                 String sortBy, String sortDir) {
    String normalizedPrefix = normalizePrefix(prefix);
    return Mono.fromFuture(s3AsyncClient.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket).prefix(normalizedPrefix).delimiter("/").build()))
        .map(response -> {
          List<FileItemDTO> all = new ArrayList<>();
          response.commonPrefixes().stream()
              .map(cp -> FileItemDTO.folder(cp.prefix(), normalizedPrefix))
              .forEach(all::add);
          response.contents().stream()
              .filter(o -> !o.key().equals(normalizedPrefix))
              .map(o -> FileItemDTO.file(o, normalizedPrefix))
              .forEach(all::add);

          Comparator<FileItemDTO> cmp = buildComparator(sortBy);
          if ("desc".equalsIgnoreCase(sortDir)) cmp = cmp.reversed();
          all.sort(cmp);

          int total     = all.size();
          int fromIndex = page * size;
          int toIndex   = Math.min(fromIndex + size, total);
          return new FileListResponseDTO(
              fromIndex >= total ? List.of() : all.subList(fromIndex, toIndex),
              total, page, size,
              (int) Math.ceil((double) total / size),
              normalizedPrefix);
        });
  }

  // ── search ────────────────────────────────────────────────
  public Mono<FileListResponseDTO> search(String prefix, String query, int page, int size) {
    String normalizedPrefix = normalizePrefix(prefix);
    String lowerQuery       = query.toLowerCase();

    return listAllS3Objects(normalizedPrefix)
        .filter(o -> getItemName(o.key()).toLowerCase().contains(lowerQuery))
        .map(o -> FileItemDTO.file(o, normalizedPrefix))
        .collectList()
        .map(allItems -> {
          int total     = allItems.size();
          int fromIndex = page * size;
          int toIndex   = Math.min(fromIndex + size, total);
          return new FileListResponseDTO(
              fromIndex >= total ? List.of() : allItems.subList(fromIndex, toIndex),
              total, page, size,
              (int) Math.ceil((double) total / size),
              normalizedPrefix);
        });
  }

  // ── file details ──────────────────────────────────────────
  public Mono<FileItemDTO> getFileDetails(String key) {
    return Mono.fromFuture(s3AsyncClient.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(key).build()))
        .map(head -> FileItemDTO.builder()
            .key(key).name(getItemName(key)).type("file")
            .size(head.contentLength()).lastModified(head.lastModified().atOffset(ZoneOffset.UTC))
            .contentType(head.contentType()).path(getParentPath(key))
            .etag(head.eTag())
            .build());
  }

  // ── pre-signed URL ────────────────────────────────────────
  public Mono<String> getPreSignedUrl(String key, int expiryMinutes) {
    return Mono.fromCallable(() -> {
      try (S3Presigner preSigner = S3Presigner.builder()
          .endpointOverride(URI.create(endpoint))
          .region(Region.US_EAST_1)
          .credentialsProvider(s3AsyncClient.serviceClientConfiguration().credentialsProvider())
          .build()) {
        return preSigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expiryMinutes))
                .getObjectRequest(r -> r.bucket(bucket).key(key))
                .build()
        ).url().toString();
      }
    }).subscribeOn(Schedulers.boundedElastic());
  }

  // ── storage stats ─────────────────────────────────────────
  public Mono<StorageStatsDTO> getStorageStats(String prefix) {
    String normalizedPrefix = normalizePrefix(prefix);
    return Mono.fromFuture(s3AsyncClient.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket).prefix(normalizedPrefix).delimiter("/").build()))
        .map(response -> {
          long totalSize    = 0;
          long totalFiles   = 0;
          long totalFolders = response.commonPrefixes().size();
          Map<String, Long> typeBreakdown = new HashMap<>();
          for (S3Object obj : response.contents()) {
            if (!obj.key().equals(normalizedPrefix) && !obj.key().endsWith("/")) {
              totalSize += obj.size();
              totalFiles++;
              typeBreakdown.merge(getExtension(obj.key()), 1L, Long::sum);
            }
          }
          return new StorageStatsDTO(totalSize, totalFiles, totalFolders,
              typeBreakdown, normalizedPrefix);
        });
  }

  // ── reactive helpers ──────────────────────────────────────
  private Mono<Void> copyObjectReactive(String src, String dst) {
    return Mono.fromFuture(s3AsyncClient.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(src)
                .destinationBucket(bucket).destinationKey(dst)
                .build()))
        .then();
  }

  private Mono<Void> copyFolderReactive(String srcPrefix, String dstPrefix) {
    return listAllS3Objects(srcPrefix)
        .flatMap(obj -> {
          String rel = obj.key().substring(srcPrefix.length());
          return copyObjectReactive(obj.key(), dstPrefix + rel);
        })
        .then();
  }

  private Mono<Void> deleteObjectReactive(String key) {
    return Mono.fromFuture(s3AsyncClient.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(key).build()))
        .then();
  }

  private Mono<Void> deleteFolderReactive(String prefix) {
    return listAllS3Objects(prefix)
        .buffer(1000)
        .flatMap(batch -> {
          List<ObjectIdentifier> ids = batch.stream()
              .map(o -> ObjectIdentifier.builder().key(o.key()).build())
              .toList();
          return Mono.fromFuture(s3AsyncClient.deleteObjects(req -> req
              .bucket(bucket)
              .delete(del -> del.objects(ids))
          ));
        })
        .then();
  }

  // paginated listing — expands continuation tokens reactively
  private Flux<S3Object> listAllS3Objects(String prefix) {
    return Mono.fromFuture(s3AsyncClient.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()))
        .expand(response -> {
          if (!Boolean.TRUE.equals(response.isTruncated())) return Mono.empty();
          return Mono.fromFuture(s3AsyncClient.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket).prefix(prefix)
                  .continuationToken(response.nextContinuationToken())
                  .build()));
        })
        .flatMap(response -> Flux.fromIterable(response.contents()));
  }

  // ── utility ───────────────────────────────────────────────
  private String normalizePrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) return "";
    String p = prefix.trim();
    if (!p.endsWith("/")) p += "/";
    if (p.startsWith("/")) p = p.substring(1);
    return p;
  }

  private String getParentPath(String key) {
    String k = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
    int idx  = k.lastIndexOf('/');
    return idx <= 0 ? "" : k.substring(0, idx + 1);
  }

  private String getItemName(String key) {
    String k = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
    int idx  = k.lastIndexOf('/');
    return idx < 0 ? k : k.substring(idx + 1);
  }

  private String getExtension(String key) {
    int dot   = key.lastIndexOf('.');
    int slash = key.lastIndexOf('/');
    return (dot > slash && dot < key.length() - 1)
        ? key.substring(dot + 1).toLowerCase() : "other";
  }

  private String sanitizeName(String name) {
    return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
  }

  private String detectContentType(String filename) {
    String ct = URLConnection.guessContentTypeFromName(filename);
    return ct != null ? ct : "application/octet-stream";
  }

  private Comparator<FileItemDTO> buildComparator(String sortBy) {
    return switch (sortBy == null ? "name" : sortBy.toLowerCase()) {
      case "size"                 -> Comparator.comparingLong(f -> f.getSize() == null ? 0 : f.getSize());
      case "lastmodified", "date" -> Comparator.comparing(
          f -> f.getLastModified() == null
              ? OffsetDateTime.MIN
              : f.getLastModified()
      );
      case "type"                 -> Comparator.comparing(FileItemDTO::getType);
      default                     -> Comparator.comparing(f -> f.getName().toLowerCase());
    };
  }
}