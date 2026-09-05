package com.yeqimin.computehub.instance;

import com.yeqimin.computehub.common.ApiResponse;
import com.yeqimin.computehub.domain.EngineScenario;
import com.yeqimin.computehub.domain.InstanceOperation;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/instances")
public class InstanceController {
  private final InstanceService service;
  private final LifecycleCommandService lifecycle;

  public InstanceController(InstanceService service, LifecycleCommandService lifecycle) {
    this.service = service;
    this.lifecycle = lifecycle;
  }

  @GetMapping
  public ApiResponse<?> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long tenantId,
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long clusterId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime startTime,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime endTime,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(
        keyword, tenantId, productId, clusterId,
        status == null ? state : status,
        startTime, endTime, sort, order, page, size));
  }

  @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}

  @PostMapping
  @PreAuthorize("hasAuthority('instance:create')")
  public ApiResponse<?> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody CreateInstanceRequest request) {
    return ApiResponse.ok(service.create(key, request));
  }

  @PostMapping("/{id}/start")
  @PreAuthorize("hasAuthority('instance:operate')")
  public ApiResponse<?> start(
      @PathVariable long id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody(required = false) LifecycleActionRequest request) {
    return ApiResponse.ok(lifecycle.submit(key, id, InstanceOperation.START, scenario(request)));
  }

  @PostMapping("/{id}/stop")
  @PreAuthorize("hasAuthority('instance:operate')")
  public ApiResponse<?> stop(
      @PathVariable long id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody(required = false) LifecycleActionRequest request) {
    return ApiResponse.ok(lifecycle.submit(key, id, InstanceOperation.STOP, scenario(request)));
  }

  @PostMapping("/{id}/restart")
  @PreAuthorize("hasAuthority('instance:operate')")
  public ApiResponse<?> restart(
      @PathVariable long id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody(required = false) LifecycleActionRequest request) {
    return ApiResponse.ok(lifecycle.submit(key, id, InstanceOperation.RESTART, scenario(request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('instance:operate')")
  public ApiResponse<?> delete(
      @PathVariable long id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestParam(defaultValue = "SUCCESS") EngineScenario scenario) {
    return ApiResponse.ok(lifecycle.submit(key, id, InstanceOperation.DELETE, scenario));
  }

  @PostMapping("/batch-actions")
  @PreAuthorize("hasAuthority('instance:operate')")
  public ApiResponse<?> batch(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody BatchInstanceActionRequest request) {
    return ApiResponse.ok(lifecycle.submitBatch(key, request));
  }

  private static EngineScenario scenario(LifecycleActionRequest request) {
    return request == null ? EngineScenario.SUCCESS : request.scenario();
  }
}
