package com.yeqimin.computehub.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LifecycleMapper {
  @Select("""
      SELECT i.id, i.instance_no instanceNo, i.tenant_id tenantId,
             i.product_id productId, i.cluster_id clusterId, i.name,
             i.engine_instance_id engineInstanceId, i.status,
             i.active_task_id activeTaskId, active.task_no activeTaskNo,
             c.code clusterCode
      FROM compute_instance i
      JOIN compute_cluster c ON c.id = i.cluster_id
      LEFT JOIN async_task active ON active.id = i.active_task_id
      WHERE i.id = #{instanceId}
      FOR UPDATE
      """)
  Map<String, Object> instanceForUpdate(long instanceId);

  @Insert("""
      INSERT INTO async_task(
        task_no, command_id, tenant_id, instance_id, state,
        retry_count, next_retry_at, operation_type,
        previous_instance_status, target_instance_status, scenario,
        actor_id, message_id, accepted_at)
      VALUES(
        #{taskNo}, #{commandId}, #{tenantId}, #{instanceId}, 'PENDING',
        0, NOW(3), #{operation}, #{previousStatus}, #{targetStatus},
        #{scenario}, #{actorId}, #{messageId}, NOW(3))
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertLifecycleTask(Map<String, Object> task);

  @Update("""
      UPDATE compute_instance
      SET active_task_id = #{taskId}, status = #{status}, version = version + 1
      WHERE id = #{instanceId} AND active_task_id IS NULL
      """)
  int setActiveTask(
      @Param("instanceId") long instanceId,
      @Param("taskId") long taskId,
      @Param("status") String status);

  @Insert("""
      INSERT INTO outbox_event(
        event_id, aggregate_type, aggregate_id, event_type, payload,
        state, retry_count, next_retry_at, task_id, command_id, message_id)
      VALUES(
        #{messageId}, 'INSTANCE', #{instanceId}, 'INSTANCE_COMMAND',
        CAST(#{payload} AS JSON), 'READY', 0, NOW(3),
        #{taskId}, #{commandId}, #{messageId})
      """)
  int insertCommandOutbox(Map<String, Object> outbox);

  @Select("""
      SELECT t.instance_id instanceId, t.id taskId, t.task_no taskNo,
             t.command_id commandId, i.status instanceStatus, t.state taskState
      FROM async_task t
      JOIN compute_instance i ON i.id = t.instance_id
      WHERE t.id = #{taskId}
      """)
  Map<String, Object> submission(long taskId);
}
