package com.yeqimin.computehub.persistence;

import com.yeqimin.computehub.audit.AuditEntry;
import com.yeqimin.computehub.realtime.RealtimeEvent;
import org.apache.ibatis.annotations.Insert;

public interface AuditMapper {
  @Insert("""
      INSERT INTO operation_audit_log(
        tenant_id, actor_id, instance_id, task_id, action,
        before_state, after_state, result, error, trace_id)
      VALUES(#{tenantId}, #{actorId}, #{instanceId}, #{taskId}, #{action},
        #{beforeState}, #{afterState}, #{result}, #{error}, #{traceId})
      """)
  int insertAudit(AuditEntry entry);

  @Insert("""
      INSERT INTO realtime_event(
        tenant_id, event_type, aggregate_type, aggregate_id, payload)
      VALUES(#{tenantId}, #{type}, #{aggregateType}, #{aggregateId},
        CAST(#{payload} AS JSON))
      """)
  int insertRealtimeEvent(RealtimeEvent event);
}
