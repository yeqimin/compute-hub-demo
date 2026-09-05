package com.yeqimin.computehub.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.audit.AuditService;
import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.EngineScenario;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStateMachine;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.domain.TransitionPlan;
import com.yeqimin.computehub.persistence.IdempotencyMapper;
import com.yeqimin.computehub.persistence.LifecycleMapper;
import com.yeqimin.computehub.realtime.RealtimeEventService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifecycleCommandTxService {
  private final LifecycleMapper mapper;
  private final IdempotencyMapper idempotency;
  private final AuditService audit;
  private final RealtimeEventService realtime;
  private final ObjectMapper json;
  private final String callbackUrl;

  public LifecycleCommandTxService(
      LifecycleMapper mapper,
      IdempotencyMapper idempotency,
      AuditService audit,
      RealtimeEventService realtime,
      ObjectMapper json,
      @Value("${compute-hub.callback-url}") String callbackUrl) {
    this.mapper = mapper;
    this.idempotency = idempotency;
    this.audit = audit;
    this.realtime = realtime;
    this.json = json;
    this.callbackUrl = callbackUrl;
  }

  @Transactional
  public LifecycleSubmission submit(
      long actorId,
      Long tenantScope,
      long instanceId,
      InstanceOperation operation,
      EngineScenario scenario,
      String idempotencyKey,
      String requestHash) {
    Map<String, Object> instance = mapper.instanceForUpdate(instanceId);
    if (instance == null || (tenantScope != null && number(instance, "tenantId") != tenantScope)) {
      throw BusinessException.notFound("实例不存在");
    }

    if (idempotency.tryStart(actorId, idempotencyKey, requestHash, "INSTANCE_LIFECYCLE") == 0) {
      return replay(actorId, idempotencyKey, requestHash);
    }

    if (instance.get("activeTaskId") != null) {
      Object activeTask = instance.get("activeTaskNo") == null
          ? instance.get("activeTaskId") : instance.get("activeTaskNo");
      throw BusinessException.conflict("实例存在活动任务: " + activeTask);
    }

    InstanceStatus current = InstanceStatus.valueOf(String.valueOf(instance.get("status")));
    TransitionPlan plan;
    try {
      plan = InstanceStateMachine.begin(current, operation);
    } catch (IllegalStateException e) {
      throw BusinessException.conflict("当前状态不允许 " + operation + ": " + current);
    }

    String taskNo = id("TASK");
    String commandId = id("CMD");
    String messageId = id("MSG");
    long tenantId = number(instance, "tenantId");

    Map<String, Object> task = new LinkedHashMap<>();
    task.put("taskNo", taskNo);
    task.put("commandId", commandId);
    task.put("tenantId", tenantId);
    task.put("instanceId", instanceId);
    task.put("operation", operation.name());
    task.put("scenario", scenario.name());
    task.put("previousStatus", plan.previous().name());
    task.put("targetStatus", plan.target().name());
    task.put("actorId", actorId);
    task.put("messageId", messageId);
    mapper.insertLifecycleTask(task);
    long taskId = number(task, "id");

    if (mapper.setActiveTask(instanceId, taskId, plan.executing().name()) != 1) {
      throw BusinessException.conflict("实例存在活动任务");
    }

    Map<String, Object> outbox = new LinkedHashMap<>();
    outbox.put("messageId", messageId);
    outbox.put("taskId", taskId);
    outbox.put("commandId", commandId);
    outbox.put("instanceId", instanceId);
    outbox.put("payload", write(commandPayload(
        messageId, taskId, commandId, instance, operation, scenario)));
    mapper.insertCommandOutbox(outbox);

    audit.appendAccepted(actorId, tenantId, instanceId, taskId, operation, plan);
    realtime.appendTaskChanged(tenantId, instanceId, taskId, operation, TaskState.PENDING);

    LifecycleSubmission submission = submission(taskId);
    idempotency.complete(actorId, idempotencyKey, taskId, write(submission));
    return submission;
  }

  private LifecycleSubmission replay(long actorId, String key, String hash) {
    Map<String, Object> old = idempotency.find(actorId, key);
    if (old == null) throw BusinessException.conflict("请求正在处理中");
    if (!hash.equals(old.get("requestHash"))) {
      throw BusinessException.conflict("Idempotency-Key 已用于不同请求");
    }
    if (!"COMPLETED".equals(old.get("status"))) {
      throw BusinessException.conflict("相同请求正在处理中，请稍后重试");
    }
    try {
      return json.readValue(String.valueOf(old.get("responseBody")), LifecycleSubmission.class);
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize lifecycle replay", e);
    }
  }

  private LifecycleSubmission submission(long taskId) {
    Map<String, Object> row = mapper.submission(taskId);
    return new LifecycleSubmission(
        number(row, "instanceId"),
        number(row, "taskId"),
        String.valueOf(row.get("taskNo")),
        String.valueOf(row.get("commandId")),
        InstanceStatus.valueOf(String.valueOf(row.get("instanceStatus"))),
        TaskState.valueOf(String.valueOf(row.get("taskState"))));
  }

  private Map<String, Object> commandPayload(
      String messageId,
      long taskId,
      String commandId,
      Map<String, Object> instance,
      InstanceOperation operation,
      EngineScenario scenario) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    payload.put("messageId", messageId);
    payload.put("taskId", taskId);
    payload.put("commandId", commandId);
    payload.put("instanceId", number(instance, "id"));
    payload.put("instanceNo", instance.get("instanceNo"));
    payload.put("engineInstanceId", instance.get("engineInstanceId"));
    payload.put("tenantId", number(instance, "tenantId"));
    payload.put("productId", number(instance, "productId"));
    payload.put("clusterCode", instance.get("clusterCode"));
    payload.put("operation", operation.name());
    payload.put("scenario", scenario.name());
    payload.put("callbackUrl", callbackUrl);
    payload.put("attempt", 0);
    return payload;
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String id(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize lifecycle command", e);
    }
  }
}
