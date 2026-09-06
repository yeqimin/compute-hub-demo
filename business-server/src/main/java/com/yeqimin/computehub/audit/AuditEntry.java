package com.yeqimin.computehub.audit;

public record AuditEntry(
    Long tenantId,
    Long actorId,
    Long instanceId,
    Long taskId,
    String action,
    String beforeState,
    String afterState,
    String result,
    String error,
    String traceId) {}
