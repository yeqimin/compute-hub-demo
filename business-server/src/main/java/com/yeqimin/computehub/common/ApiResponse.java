package com.yeqimin.computehub.common;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, String message, T data, String traceId) {
  public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("SUCCESS", "ok", data, trace()); }
  public static ApiResponse<Void> error(String code, String message) { return new ApiResponse<>(code, message, null, trace()); }
  private static String trace() { return MDC.get("traceId") == null ? "-" : MDC.get("traceId"); }
}
