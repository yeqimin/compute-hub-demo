package com.yeqimin.computehub.instance;

import java.util.List;

public record BatchInstanceActionResponse(
    int successCount,
    int skippedCount,
    int failedCount,
    List<Item> items) {
  public BatchInstanceActionResponse {
    items = List.copyOf(items);
  }

  public record Item(long instanceId, String code, String message, Long taskId) {}
}
