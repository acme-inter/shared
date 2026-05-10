package com.acme.shared.payload;

import com.acme.shared.payload.table.FilterDTO;
import com.acme.shared.payload.table.SortDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedResponse<T> {

  @Builder.Default private boolean success = false;
  private String        message;
  private String        code;
  private List<T> data;
  private int           totalPages;
  private long          totalElements;
  private int           index;
  private int           size;
  private String        error;
  private List<FilterDTO> filters;
  private List<SortDTO>   sorts;
  private String        viewId;
  private Long          nextCursor;
  private boolean       hasNext;

  public static <T> PagedResponse<T> success(String msg, int index, int size) {
    return PagedResponse.<T>builder()
        .success(true).message(msg)
        .data(List.of())
        .totalPages(0).totalElements(0)
        .index(index).size(size).build();
  }

  public static <T> PagedResponse<T> success(String message, List<T> data,
                                             int totalPages, long totalElements,
                                             int index, int size) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data).totalPages(totalPages).totalElements(totalElements)
        .index(index).size(size).build();
  }

  public static <T> PagedResponse<T> success(String message, List<T> data,
                                             int totalPages, long totalElements,
                                             int index, int size,
                                             List<FilterDTO> filters,
                                             List<SortDTO> sorts,
                                             String viewId) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data).filters(filters).sorts(sorts).viewId(viewId)
        .totalPages(totalPages).totalElements(totalElements)
        .index(index).size(size).build();
  }

  public static <T> PagedResponse<T> cursorEmptySuccess(String message, boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(List.of())
        .hasNext(hasNext).build();
  }

  public static <T> PagedResponse<T> cursorSuccess(String message, List<T> data, boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data != null ? data : List.of())
        .hasNext(hasNext).build();
  }

  public static <T> PagedResponse<T> cursorSuccess(String message, List<T> data,
                                                   Long nextCursor, boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data != null ? data : List.of())
        .nextCursor(nextCursor).hasNext(hasNext).build();
  }

  public static <T> PagedResponse<T> error(String message, String error) {
    return PagedResponse.<T>builder().data(List.of())
        .success(false).message(message).error(error).build();
  }
}
