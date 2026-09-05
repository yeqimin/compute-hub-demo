package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.TaskState;
import java.time.LocalDateTime;

public record TaskQuery(
    Long tenantId,
    InstanceOperation operation,
    TaskState state,
    String commandId,
    String instanceNo,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    String sort,
    String order,
    int page,
    int size) {}
