package com.yeqimin.computehub.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiResponse<Void>> business(BusinessException e) { return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage())); }

  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
  ResponseEntity<ApiResponse<Void>> validation(Exception e) { return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "请求参数不合法")); }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiResponse<Void>> denied() { return ResponseEntity.status(403).body(ApiResponse.error("FORBIDDEN", "没有操作权限")); }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Void>> unknown(Exception e) { return ResponseEntity.status(500).body(ApiResponse.error("INTERNAL_ERROR", e.getMessage())); }
}
