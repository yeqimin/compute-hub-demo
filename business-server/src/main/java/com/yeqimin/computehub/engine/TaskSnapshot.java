package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.domain.TransitionPlan;
import java.util.Set;

public record TaskSnapshot(
    long id,
    long instanceId,
    long tenantId,
    String commandId,
    InstanceOperation operation,
    TaskState state,
    InstanceStatus previousStatus,
    InstanceStatus executingStatus,
    InstanceStatus targetStatus,
    int retryCount,
    String traceId) {
  public boolean terminal() {
    return Set.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.DEAD).contains(state);
  }

  public TransitionPlan transitionPlan() {
    return new TransitionPlan(previousStatus, executingStatus, targetStatus, operation);
  }
}
