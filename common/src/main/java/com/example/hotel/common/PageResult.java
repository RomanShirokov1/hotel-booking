package com.example.hotel.common;

import java.util.List;

public record PageResult<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {
  public static void validate(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGE", "page >= 0 and 1 <= size <= 100 required");
  }

  public static <T> PageResult<T> of(List<T> items, int page, int size) {
    validate(page, size);
    int start = (int) Math.min((long) page * size, items.size());
    return new PageResult<>(
        items.subList(start, Math.min(start + size, items.size())),
        page,
        size,
        items.size(),
        (items.size() + size - 1) / size);
  }
}
