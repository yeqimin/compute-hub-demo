package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.ApiResponse;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.TaskState;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final TaskRecoveryService service;

  public TaskController(TaskRecoveryService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @RequestParam(required = false) Long tenantId,
      @RequestParam(required = false) InstanceOperation operation,
      @RequestParam(required = false) TaskState state,
      @RequestParam(required = false) String commandId,
      @RequestParam(required = false) String instanceNo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime startedAt,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime endedAt,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(new TaskQuery(
        tenantId, operation, state, commandId, instanceNo, startedAt, endedAt,
        sort, order, page, size)));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> detail(@PathVariable long id) {
    return ApiResponse.ok(service.detail(id));
  }

  @PostMapping("/{id}/retry")
  @PreAuthorize("hasAuthority('instance:retry')")
  public ApiResponse<?> retry(@PathVariable long id) {
    return ApiResponse.ok(service.retry(id));
  }

  @PostMapping("/{id}/reconcile")
  @PreAuthorize("hasAuthority('instance:retry')")
  public ApiResponse<?> reconcile(@PathVariable long id) {
    return ApiResponse.ok(service.reconcile(id));
  }
}
