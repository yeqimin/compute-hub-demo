package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;

public record SettlementResult(
    boolean duplicate,
    long instanceId,
    long taskId,
    InstanceStatus instanceStatus,
    TaskState taskState) {}
