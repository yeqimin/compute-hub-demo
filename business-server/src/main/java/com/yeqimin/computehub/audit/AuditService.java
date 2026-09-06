package com.yeqimin.computehub.audit;

import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TransitionPlan;
import com.yeqimin.computehub.persistence.AuditMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditMapper mapper;

  public AuditService(AuditMapper mapper) {
    this.mapper = mapper;
  }

  public void appendAccepted(
      long actorId,
      long tenantId,
      long instanceId,
      long taskId,
      InstanceOperation operation,
      TransitionPlan plan) {
    mapper.insertAudit(new AuditEntry(
        tenantId,
        actorId,
        instanceId,
        taskId,
        operation.name(),
        plan.previous().name(),
        plan.executing().name(),
        "ACCEPTED",
        null,
        traceId()));
  }

  public void appendCallback(
      Long actorId,
      long tenantId,
      long instanceId,
      long taskId,
      InstanceOperation operation,
      InstanceStatus before,
      InstanceStatus after,
      String result,
      String error) {
    mapper.insertAudit(new AuditEntry(
        tenantId,
        actorId,
        instanceId,
        taskId,
        operation.name(),
        before.name(),
        after.name(),
        result,
        error,
        traceId()));
  }

  private static String traceId() {
    String traceId = MDC.get("traceId");
    return traceId == null ? "-" : traceId;
  }
}
