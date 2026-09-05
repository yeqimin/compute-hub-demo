package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.persistence.TaskMapper;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.security.CurrentUser;
import com.yeqimin.computehub.security.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskRecoveryService {
  private static final Set<String> OPERATOR_ROLES = Set.of("PLATFORM_ADMIN", "TENANT_ADMIN");
  private static final Set<String> SAFE_SORTS =
      Set.of("id", "createdAt", "updatedAt", "state", "operation", "retryCount");
  private static final Set<String> SETTLED_ENGINE_STATES =
      Set.of("SUCCEEDED", "RUNNING", "STOPPED", "DELETED", "FAILED");

  private final TaskMapper tasks;
  private final EngineClient engine;
  private final SettlementService settlement;

  public TaskRecoveryService(TaskMapper tasks, EngineClient engine, SettlementService settlement) {
    this.tasks = tasks;
    this.engine = engine;
    this.settlement = settlement;
  }

  public Map<String, Object> list(TaskQuery query) {
    UserPrincipal user = CurrentUser.get();
    Long tenantId = user.platformAdmin() ? query.tenantId() : user.tenantId();
    int page = Math.max(query.page(), 1);
    int size = Math.min(Math.max(query.size(), 1), 100);
    String sort = SAFE_SORTS.contains(query.sort()) ? query.sort() : "createdAt";
    String order = "asc".equalsIgnoreCase(query.order()) ? "asc" : "desc";
    String operation = query.operation() == null ? null : query.operation().name();
    String state = query.state() == null ? null : query.state().name();
    String commandId = blankToNull(query.commandId());
    String instanceNo = blankToNull(query.instanceNo());
    List<Map<String, Object>> items = tasks.taskItems(
        tenantId, operation, state, commandId, instanceNo, query.startedAt(), query.endedAt(),
        sort, order, (page - 1) * size, size);
    long total = tasks.taskCount(
        tenantId, operation, state, commandId, instanceNo, query.startedAt(), query.endedAt());
    return Map.of("items", items, "total", total, "page", page, "size", size);
  }

  public Map<String, Object> detail(long taskId) {
    Map<String, Object> row = tasks.taskDetail(taskId, tenantScope());
    if (row == null) throw BusinessException.notFound("任务不存在");
    Map<String, Object> detail = new LinkedHashMap<>(row);
    ensureNullableFields(detail);
    detail.put("timeline", timeline(detail));
    return detail;
  }

  @Transactional
  public Map<String, Object> retry(long taskId) {
    requireOperator();
    Map<String, Object> task = tasks.recoveryTaskForUpdate(taskId, tenantScope());
    if (task == null) throw BusinessException.notFound("任务不存在");
    if (!TaskState.UNKNOWN.name().equals(task.get("state"))) {
      throw BusinessException.conflict("任务当前无需人工重试");
    }
    long instanceId = number(task, "instanceId");
    InstanceOperation operation = InstanceOperation.valueOf(String.valueOf(task.get("operation")));
    if (tasks.manualRetry(taskId) != 1
        || tasks.manualOutbox(taskId) != 1
        || tasks.restoreExecuting(instanceId, taskId, OutboxWorker.executing(operation).name()) != 1) {
      throw BusinessException.conflict("任务恢复状态已变更");
    }
    return Map.of(
        "taskId", taskId,
        "commandId", String.valueOf(task.get("commandId")),
        "status", "RETRYING");
  }

  public Map<String, Object> reconcile(long taskId) {
    requireOperator();
    Map<String, Object> task = tasks.recoveryTask(taskId, tenantScope());
    if (task == null) throw BusinessException.notFound("任务不存在");
    if (!TaskState.UNKNOWN.name().equals(task.get("state"))) {
      throw BusinessException.conflict("任务当前无需人工对账");
    }
    String commandId = String.valueOf(task.get("commandId"));
    InstanceOperation operation = InstanceOperation.valueOf(String.valueOf(task.get("operation")));
    CommandStatusReply fact = engine.status(commandId);
    validateEngineIdentity(fact, commandId, operation);
    String status = fact.getStatus();
    if (!SETTLED_ENGINE_STATES.contains(status)) {
      return Map.of(
          "taskId", taskId,
          "commandId", commandId,
          "status", status,
          "settled", false);
    }
    boolean failed = "FAILED".equals(status);
    String instanceState = fact.getInstanceState().isBlank() ? status : fact.getInstanceState();
    if ("SUCCEEDED".equals(status) && fact.getInstanceState().isBlank()) {
      throw BusinessException.conflict("引擎成功事实缺少实例状态");
    }
    InstanceStatus callbackState;
    try {
      callbackState = InstanceStatus.valueOf(instanceState);
    } catch (IllegalArgumentException exception) {
      throw BusinessException.conflict("引擎返回了不支持的实例状态");
    }
    SettlementResult result = settlement.settle(new EngineEventRequest(
        reconciliationEventId(taskId), commandId, operation,
        failed ? "FAILED" : "SUCCEEDED", callbackState,
        fact.getEngineInstanceId(), "人工对账"), payloadHash());
    return Map.of(
        "taskId", result.taskId(),
        "commandId", commandId,
        "status", status,
        "settled", true,
        "taskState", result.taskState().name(),
        "instanceStatus", result.instanceStatus().name());
  }

  private Long tenantScope() {
    UserPrincipal user = CurrentUser.get();
    return user.platformAdmin() ? null : user.tenantId();
  }

  private static void requireOperator() {
    UserPrincipal user = CurrentUser.get();
    if (user.roles().stream().noneMatch(OPERATOR_ROLES::contains)) {
      throw new AccessDeniedException("只读角色不能处置任务");
    }
  }

  private static void validateEngineIdentity(
      CommandStatusReply fact, String commandId, InstanceOperation operation) {
    if (!commandId.equals(fact.getCommandId())) {
      throw BusinessException.conflict("引擎返回的命令与请求任务不一致");
    }
    if (!"NOT_FOUND".equals(fact.getStatus())
        && !operation.name().equals(fact.getOperation().name())) {
      throw BusinessException.conflict("引擎返回的操作与请求任务不一致");
    }
  }

  private static List<Map<String, Object>> timeline(Map<String, Object> detail) {
    List<Map<String, Object>> result = new ArrayList<>();
    result.add(stage("TRANSACTION", "COMPLETED", detail.get("createdAt")));
    result.add(stage("OUTBOX", String.valueOf(detail.get("outboxState")),
        detail.get("outboxCreatedAt")));
    result.add(stage("ENGINE", detail.get("acceptedAt") == null ? "PENDING" : "ACCEPTED",
        detail.get("acceptedAt")));
    result.add(stage("CALLBACK", detail.get("engineEventId") == null ? "PENDING" : "RECEIVED",
        detail.get("finishedAt")));
    result.add(stage("SETTLEMENT", terminal(String.valueOf(detail.get("state")))
        ? String.valueOf(detail.get("state")) : "PENDING", detail.get("finishedAt")));
    return List.copyOf(result);
  }

  private static Map<String, Object> stage(String stage, String status, Object time) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("stage", stage);
    value.put("status", status);
    value.put("time", time);
    return value;
  }

  private static boolean terminal(String state) {
    return Set.of("SUCCEEDED", "FAILED", "DEAD", "UNKNOWN").contains(state);
  }

  private static void ensureNullableFields(Map<String, Object> detail) {
    for (String key : List.of(
        "lastError", "engineEventId", "deadlineAt", "finishedAt", "sourceTaskId",
        "outboxLastError", "outboxPublishedAt")) {
      detail.putIfAbsent(key, null);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String reconciliationEventId(long taskId) {
    return "RECON-" + taskId + "-" + UUID.randomUUID().toString().replace("-", "");
  }

  private static String payloadHash() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }
}
