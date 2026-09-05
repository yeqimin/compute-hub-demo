package com.yeqimin.computehub.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.persistence.AuditMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RealtimeEventService {
  private final AuditMapper mapper;
  private final ObjectMapper json;

  public RealtimeEventService(AuditMapper mapper, ObjectMapper json) {
    this.mapper = mapper;
    this.json = json;
  }

  public void appendTaskChanged(
      long tenantId,
      long instanceId,
      long taskId,
      InstanceOperation operation,
      TaskState taskState) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("taskId", taskId);
    payload.put("operation", operation.name());
    payload.put("taskState", taskState.name());
    mapper.insertRealtimeEvent(new RealtimeEvent(
        0,
        tenantId,
        "TASK_CHANGED",
        "INSTANCE",
        instanceId,
        write(payload)));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize realtime event", e);
    }
  }
}
