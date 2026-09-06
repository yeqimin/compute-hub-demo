package com.yeqimin.computehub.instance;

import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;

public record LifecycleSubmission(
    long instanceId,
    long taskId,
    String taskNo,
    String commandId,
    InstanceStatus instanceStatus,
    TaskState taskState) {}
