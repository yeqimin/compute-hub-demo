package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.persistence.*;
import com.yeqimin.computehub.security.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController @RequestMapping("/api/v1/tasks")
public class TaskController {
  private final InstanceMapper instances;private final TaskMapper tasks;private final EngineClient engine;private final SettlementService settlement;
  public TaskController(InstanceMapper instances,TaskMapper tasks,EngineClient engine,SettlementService settlement){this.instances=instances;this.tasks=tasks;this.engine=engine;this.settlement=settlement;}
  @PostMapping("/{id}/retry")
  @PreAuthorize("hasAuthority('instance:retry')")
  @Transactional
  public ApiResponse<?> retry(@PathVariable long id) {
    Map<String,Object> task = instances.task(id);
    if (task == null) throw BusinessException.notFound("任务不存在");
    UserPrincipal user = CurrentUser.get();
    if (!user.platformAdmin() && num(task,"tenantId") != user.tenantId()) {
      throw BusinessException.notFound("任务不存在");
    }
    String commandId = String.valueOf(task.get("commandId"));
    InstanceOperation operation = InstanceOperation.valueOf(String.valueOf(task.get("operation")));
    CommandStatusReply state = engine.status(commandId);
    validateEngineIdentity(state, commandId, operation);
    if (Set.of("RUNNING","STOPPED","DELETED","FAILED").contains(state.getStatus())) {
      String result = "FAILED".equals(state.getStatus()) ? "FAILED" : "SUCCEEDED";
      String instanceState = state.getInstanceState().isBlank()
          ? state.getStatus() : state.getInstanceState();
      return ApiResponse.ok(settlement.settle(new EngineEventRequest(
          "RECON-" + UUID.randomUUID(), commandId, operation, result,
          InstanceStatus.valueOf(instanceState), state.getEngineInstanceId(), "人工对账"),
          UUID.randomUUID().toString().replace("-", "")));
    }
    if (!"UNKNOWN".equals(task.get("taskState"))) {
      throw BusinessException.conflict("任务当前无需人工重试");
    }
    long instanceId = num(task, "instanceId");
    if (tasks.manualRetry(id) != 1
        || tasks.manualOutbox(id) != 1
        || tasks.restoreExecuting(instanceId, id, OutboxWorker.executing(operation).name()) != 1) {
      throw BusinessException.conflict("任务恢复状态已变更");
    }
    return ApiResponse.ok(Map.of("status", "RETRYING"));
  }

  private static void validateEngineIdentity(
      CommandStatusReply state, String commandId, InstanceOperation operation) {
    if (!commandId.equals(state.getCommandId())) {
      throw BusinessException.conflict("引擎返回的命令与请求任务不一致");
    }
    if (!"NOT_FOUND".equals(state.getStatus())
        && !operation.name().equals(state.getOperation().name())) {
      throw BusinessException.conflict("引擎返回的操作与请求任务不一致");
    }
  }
  private static long num(Map<String,Object>m,String k){return((Number)m.get(k)).longValue();}
}
