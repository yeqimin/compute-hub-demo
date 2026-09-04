package com.yeqimin.computehub.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  public BusinessException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
  public HttpStatus status() { return status; }
  public String code() { return code; }
  public static BusinessException badRequest(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message); }
  public static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, "CONFLICT", message); }
  public static BusinessException forbidden(String message) { return new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", message); }
  public static BusinessException notFound(String message) { return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
}
