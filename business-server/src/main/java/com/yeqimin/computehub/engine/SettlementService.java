package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.audit.AuditService;
import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStateMachine;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.domain.TransitionPlan;
import com.yeqimin.computehub.persistence.BillingMapper;
import com.yeqimin.computehub.persistence.TaskMapper;
import com.yeqimin.computehub.realtime.RealtimeEventService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
  private static final Set<TaskState> TERMINAL_TASK_STATES =
      Set.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.DEAD);

  private final BillingMapper billing;
  private final TaskMapper tasks;
  private final AuditService audit;
  private final RealtimeEventService realtime;

  public SettlementService(
      BillingMapper billing,
      TaskMapper tasks,
      AuditService audit,
      RealtimeEventService realtime) {
    this.billing = billing;
    this.tasks = tasks;
    this.audit = audit;
    this.realtime = realtime;
  }

  @Transactional
  public SettlementResult settle(EngineEventRequest event, String payloadHash) {
    validate(event, payloadHash);
    String result = event.result().toUpperCase(Locale.ROOT);
    Map<String,Object> inbox = new HashMap<>();
    inbox.put("eventId", event.eventId());
    inbox.put("commandId", event.commandId());
    inbox.put("eventType", event.operation().name() + ":" + result);
    inbox.put("payloadHash", payloadHash);
    if (tasks.insertInbox(inbox) == 0) {
      Map<String,Object> existing = tasks.inboxEvent(event.eventId());
      if (existing == null
          || !event.commandId().equals(existing.get("commandId"))
          || !payloadHash.equals(existing.get("payloadHash"))) {
        throw BusinessException.conflict("engine_event_id 已用于不同回调");
      }
      return previousResult(event.commandId());
    }

    Map<String,Object> row = tasks.callbackTaskForUpdate(event.commandId());
    if (row == null) throw BusinessException.notFound("异步命令不存在");
    TaskSnapshot task = snapshot(row);
    Map<String,Object> instance = tasks.callbackInstanceForUpdate(task.instanceId());
    if (instance == null) throw BusinessException.notFound("实例不存在");
    InstanceStatus current = InstanceStatus.valueOf(String.valueOf(instance.get("status")));

    if (TERMINAL_TASK_STATES.contains(task.state())) {
      return ignored(task, row, current, event,
          "任务已有明确结果，忽略后续回调");
    }
    if (event.operation() != task.operation()) {
      return ignored(task, row, current, event,
          "回调操作与命令操作不一致");
    }
    Object activeTaskId = instance.get("activeTaskId");
    if (!(activeTaskId instanceof Number number) || number.longValue() != task.id()) {
      return ignored(task, row, current, event,
          "回调命令不是实例的当前活动任务");
    }
    if (current != task.executingStatus() && current != InstanceStatus.UNKNOWN) {
      return ignored(task, row, current, event,
          "实例状态与回调任务不匹配");
    }

    boolean succeeded = "SUCCEEDED".equals(result);
    InstanceStatus next = succeeded
        ? InstanceStateMachine.success(task.transitionPlan())
        : InstanceStateMachine.failure(task.transitionPlan());
    if (!callbackStateMatches(event.instanceStatus(), next, succeeded)) {
      return ignored(task, row, current, event,
          "引擎实例状态与操作结果不一致");
    }

    if (task.operation() == InstanceOperation.CREATE) {
      settleCreationWallet(row, succeeded, event.message());
      if (tasks.finishCreationOrder(task.id(), succeeded ? "COMPLETED" : "FAILED") != 1) {
        throw BusinessException.conflict("创建订单结算失败");
      }
    }
    if (tasks.finishCallbackInstance(
        task.instanceId(), task.id(), next.name(), emptyToNull(event.engineInstanceId())) != 1) {
      throw BusinessException.conflict("实例活动任务已变更");
    }
    TaskState finalTaskState = succeeded ? TaskState.SUCCEEDED : TaskState.FAILED;
    String error = succeeded ? null : emptyToNull(event.message());
    if (tasks.finishCallbackTask(task.id(), finalTaskState.name(), event.eventId(), error) != 1) {
      throw BusinessException.conflict("任务结算失败");
    }
    tasks.outboxDoneForTask(task.id());
    audit.appendCallback(numberOrNull(row, "actorId"), task.tenantId(), task.instanceId(),
        task.id(), task.operation(), current, next, finalTaskState.name(), error);
    realtime.appendTaskChanged(
        task.tenantId(), task.instanceId(), task.id(), task.operation(), finalTaskState);
    return new SettlementResult(false, task.instanceId(), task.id(), next, finalTaskState);
  }

  private SettlementResult ignored(
      TaskSnapshot task,
      Map<String,Object> row,
      InstanceStatus current,
      EngineEventRequest event,
      String reason) {
    audit.appendCallback(numberOrNull(row, "actorId"), task.tenantId(), task.instanceId(),
        task.id(), event.operation(), current, current, "IGNORED_CONFLICT", reason);
    return new SettlementResult(true, task.instanceId(), task.id(), current, task.state());
  }

  private SettlementResult previousResult(String commandId) {
    Map<String,Object> row = tasks.callbackResult(commandId);
    if (row == null) throw BusinessException.notFound("异步命令不存在");
    return new SettlementResult(
        true,
        number(row, "instanceId"),
        number(row, "taskId"),
        InstanceStatus.valueOf(String.valueOf(row.get("instanceStatus"))),
        TaskState.valueOf(String.valueOf(row.get("taskState"))));
  }

  private void settleCreationWallet(Map<String,Object> task, boolean succeeded, String message) {
    long tenantId = number(task, "tenantId");
    long amount = number(task, "amountCent");
    Map<String,Object> wallet = billing.walletForUpdate(tenantId);
    if (wallet == null) throw BusinessException.notFound("租户钱包不存在");
    long available = number(wallet, "availableCent");
    long frozen = number(wallet, "frozenCent");
    if (frozen < amount) throw BusinessException.conflict("冻结余额不足，拒绝重复结算");
    long released = succeeded ? 0 : amount;
    long availableAfter = available + released;
    long frozenAfter = frozen - amount;
    if (billing.settleFrozen(tenantId, amount, released) != 1) {
      throw BusinessException.conflict("钱包结算失败");
    }
    String type = succeeded ? "DEDUCT" : "UNFREEZE";
    String defaultRemark = succeeded ? "实例创建成功扣款" : "实例创建失败解冻";
    Map<String,Object> ledger = new HashMap<>();
    ledger.put("ledgerNo", "LED-" + uuid());
    ledger.put("tenantId", tenantId);
    ledger.put("bizNo", String.valueOf(task.get("orderNo")));
    ledger.put("type", type);
    ledger.put("deltaAvailable", released);
    ledger.put("deltaFrozen", -amount);
    ledger.put("availableAfter", availableAfter);
    ledger.put("frozenAfter", frozenAfter);
    ledger.put("remark", emptyToNull(message) == null
        ? defaultRemark : defaultRemark + ": " + message);
    if (billing.insertLedger(ledger) != 1) {
      throw BusinessException.conflict("创建费用已结算");
    }
  }

  private static TaskSnapshot snapshot(Map<String,Object> row) {
    InstanceOperation operation = InstanceOperation.valueOf(String.valueOf(row.get("operation")));
    InstanceStatus previous = InstanceStatus.valueOf(String.valueOf(row.get("previousStatus")));
    TransitionPlan plan;
    try {
      plan = InstanceStateMachine.begin(previous, operation);
    } catch (IllegalStateException exception) {
      throw BusinessException.conflict("任务状态转换快照不合法");
    }
    InstanceStatus target = InstanceStatus.valueOf(String.valueOf(row.get("targetStatus")));
    if (plan.target() != target) {
      throw BusinessException.conflict("任务目标状态不合法");
    }
    return new TaskSnapshot(
        number(row, "taskId"),
        number(row, "instanceId"),
        number(row, "tenantId"),
        String.valueOf(row.get("commandId")),
        operation,
        TaskState.valueOf(String.valueOf(row.get("taskState"))),
        previous,
        plan.executing(),
        target,
        ((Number) row.get("retryCount")).intValue(),
        null);
  }

  private static boolean callbackStateMatches(
      InstanceStatus callback, InstanceStatus expected, boolean succeeded) {
    return callback == expected || (!succeeded && callback == InstanceStatus.FAILED);
  }

  private static void validate(EngineEventRequest event, String payloadHash) {
    if (event == null
        || blank(event.eventId())
        || blank(event.commandId())
        || event.operation() == null
        || blank(event.result())
        || event.instanceStatus() == null
        || blank(payloadHash)) {
      throw BusinessException.badRequest("回调数据不完整");
    }
    String result = event.result().toUpperCase(Locale.ROOT);
    if (!Set.of("SUCCEEDED", "FAILED").contains(result)) {
      throw BusinessException.badRequest("不支持的引擎操作结果");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String emptyToNull(String value) {
    return blank(value) ? null : value;
  }

  private static long number(Map<String,Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Long numberOrNull(Map<String,Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String uuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
  }
}
