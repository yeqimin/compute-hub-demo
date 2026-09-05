package com.yeqimin.computehub.persistence;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface TaskMapper {
  @Insert("INSERT IGNORE INTO inbox_event(engine_event_id,command_id,event_type,payload_hash) VALUES(#{eventId},#{commandId},#{eventType},#{payloadHash})")
  int insertInbox(Map<String,Object> row);

  @Select("SELECT engine_event_id eventId,command_id commandId,event_type eventType,payload_hash payloadHash FROM inbox_event WHERE engine_event_id=#{eventId}")
  Map<String,Object> inboxEvent(String eventId);

  @Select("""
      SELECT t.id taskId,t.instance_id instanceId,t.tenant_id tenantId,
        t.command_id commandId,t.operation_type operation,t.state taskState,
        t.previous_instance_status previousStatus,
        t.target_instance_status targetStatus,t.retry_count retryCount,
        t.actor_id actorId,o.order_no orderNo,o.amount_cent amountCent
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      JOIN compute_order o ON o.id=i.order_id
      WHERE t.command_id=#{commandId}
      FOR UPDATE
      """)
  Map<String,Object> callbackTaskForUpdate(String commandId);

  @Select("""
      SELECT id,status,active_task_id activeTaskId,
        engine_instance_id engineInstanceId
      FROM compute_instance
      WHERE id=#{instanceId}
      FOR UPDATE
      """)
  Map<String,Object> callbackInstanceForUpdate(long instanceId);

  @Select("""
      SELECT t.id taskId,t.instance_id instanceId,t.state taskState,
        i.status instanceStatus
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      WHERE t.command_id=#{commandId}
      """)
  Map<String,Object> callbackResult(String commandId);

  @Update("""
      UPDATE async_task
      SET state=#{state},engine_event_id=#{eventId},last_error=#{error},
        finished_at=NOW(3),version=version+1
      WHERE id=#{taskId}
      """)
  int finishCallbackTask(
      @Param("taskId")long taskId,
      @Param("state")String state,
      @Param("eventId")String eventId,
      @Param("error")String error);

  @Update("""
      UPDATE compute_instance
      SET status=#{status},
        engine_instance_id=COALESCE(#{engineId},engine_instance_id),
        active_task_id=NULL,
        deleted_at=CASE WHEN #{status}='DELETED' THEN NOW(3) ELSE deleted_at END,
        version=version+1
      WHERE id=#{instanceId} AND active_task_id=#{taskId}
      """)
  int finishCallbackInstance(
      @Param("instanceId")long instanceId,
      @Param("taskId")long taskId,
      @Param("status")String status,
      @Param("engineId")String engineId);

  @Update("""
      UPDATE compute_order o
      JOIN compute_instance i ON i.order_id=o.id
      JOIN async_task t ON t.instance_id=i.id
      SET o.status=#{status}
      WHERE t.id=#{taskId} AND t.operation_type='CREATE'
      """)
  int finishCreationOrder(@Param("taskId")long taskId,@Param("status")String status);

  @Update("UPDATE outbox_event SET state='DONE',last_error=NULL WHERE task_id=#{taskId}")
  int outboxDoneForTask(long taskId);

  @Select("SELECT o.id,o.event_id eventId,o.aggregate_id instanceId,o.payload,o.retry_count retryCount,t.id taskId,t.command_id commandId,t.tenant_id tenantId,t.operation_type operation,t.retry_count taskRetryCount,i.instance_no instanceNo,i.engine_instance_id engineInstanceId,i.product_id productId,i.scenario,c.code clusterCode FROM outbox_event o JOIN async_task t ON t.id=o.task_id JOIN compute_instance i ON i.id=o.aggregate_id JOIN compute_cluster c ON c.id=i.cluster_id WHERE o.event_type IN ('CREATE_INSTANCE','INSTANCE_COMMAND') AND o.state='READY' AND o.next_retry_at<=NOW(3) ORDER BY o.id LIMIT 1 FOR UPDATE SKIP LOCKED") Map<String,Object> nextOutbox();
  @Update("UPDATE outbox_event SET state='WAITING_CALLBACK',last_error=NULL WHERE id=#{id}") int outboxWaiting(long id);
  @Update("UPDATE async_task SET state='WAITING_CALLBACK',deadline_at=DATE_ADD(NOW(3),INTERVAL 3 SECOND),last_error=NULL,version=version+1 WHERE id=#{id}") int taskWaiting(long id);
  @Update("UPDATE outbox_event SET state=#{state},retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error=#{error} WHERE id=#{id}") int outboxFailure(@Param("id")long id,@Param("state")String state,@Param("delay")int delay,@Param("error")String error);
  @Update("UPDATE async_task SET state=#{state},retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error=#{error},version=version+1 WHERE id=#{id}") int taskFailure(@Param("id")long id,@Param("state")String state,@Param("delay")int delay,@Param("error")String error);
  @Select("SELECT t.id taskId,t.instance_id instanceId,t.operation_type operation,t.retry_count retryCount,o.id outboxId FROM async_task t JOIN outbox_event o ON o.task_id=t.id WHERE t.state='WAITING_CALLBACK' AND t.deadline_at<=NOW(3) ORDER BY t.id LIMIT 20 FOR UPDATE SKIP LOCKED") List<Map<String,Object>> expiredTasks();
  @Update("UPDATE async_task SET state='READY',retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error='等待回调超时',version=version+1 WHERE id=#{taskId} AND state='WAITING_CALLBACK'") int retryExpired(@Param("taskId")long taskId,@Param("delay")int delay);
  @Update("UPDATE outbox_event SET state='READY',retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error='等待回调超时' WHERE id=#{outboxId}") int retryOutbox(@Param("outboxId")long outboxId,@Param("delay")int delay);
  @Update("UPDATE async_task SET state='UNKNOWN',last_error='重试耗尽，等待人工对账',version=version+1 WHERE id=#{taskId}") int taskUnknown(long taskId);
  @Update("UPDATE outbox_event SET state='DEAD',last_error='重试耗尽，等待人工对账' WHERE id=#{outboxId}") int outboxDead(long outboxId);
  @Update("UPDATE compute_instance SET status='UNKNOWN' WHERE id=#{instanceId} AND active_task_id=#{taskId} AND status=#{executingStatus}")
  int instanceUnknown(
      @Param("instanceId")long instanceId,
      @Param("taskId")long taskId,
      @Param("executingStatus")String executingStatus);
  @Update("UPDATE async_task SET state='READY',retry_count=0,manual_retry_count=manual_retry_count+1,next_retry_at=NOW(3),last_error=NULL,version=version+1 WHERE id=#{taskId} AND state='UNKNOWN'") int manualRetry(long taskId);
  @Update("UPDATE outbox_event SET state='READY',retry_count=0,next_retry_at=NOW(3),last_error=NULL WHERE task_id=#{taskId}") int manualOutbox(long taskId);
  @Update("UPDATE outbox_event SET state='DONE',last_error=NULL WHERE task_id=#{taskId}") int outboxDone(long taskId);
  @Update("UPDATE compute_instance SET status=#{executingStatus},version=version+1 WHERE id=#{instanceId} AND active_task_id=#{taskId} AND status IN ('UNKNOWN',#{executingStatus})")
  int restoreExecuting(
      @Param("instanceId")long instanceId,
      @Param("taskId")long taskId,
      @Param("executingStatus")String executingStatus);

  @Select("""
      <script>
      SELECT t.id,t.task_no taskNo,t.command_id commandId,t.tenant_id tenantId,
        tn.name tenantName,t.instance_id instanceId,i.instance_no instanceNo,
        i.name instanceName,i.status instanceStatus,t.operation_type operation,
        t.state,t.retry_count retryCount,t.manual_retry_count manualRetryCount,
        t.next_retry_at nextRetryAt,t.deadline_at deadlineAt,t.last_error lastError,
        t.message_id messageId,t.engine_event_id engineEventId,
        t.accepted_at acceptedAt,t.finished_at finishedAt,
        t.created_at createdAt,t.updated_at updatedAt,
        o.state outboxState,o.retry_count outboxRetryCount
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      JOIN tenant tn ON tn.id=t.tenant_id
      LEFT JOIN outbox_event o ON o.id=(
        SELECT MAX(o2.id) FROM outbox_event o2 WHERE o2.task_id=t.id)
      WHERE (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
      <if test="operation != null">AND t.operation_type=#{operation}</if>
      <if test="state != null">AND t.state=#{state}</if>
      <if test="commandId != null">AND t.command_id=#{commandId}</if>
      <if test="instanceNo != null">AND i.instance_no=#{instanceNo}</if>
      <if test="startedAt != null">AND t.created_at&gt;=#{startedAt}</if>
      <if test="endedAt != null">AND t.created_at&lt;=#{endedAt}</if>
      ORDER BY
      <choose>
        <when test="sort == 'id'">t.id</when>
        <when test="sort == 'updatedAt'">t.updated_at</when>
        <when test="sort == 'state'">t.state</when>
        <when test="sort == 'operation'">t.operation_type</when>
        <when test="sort == 'retryCount'">t.retry_count</when>
        <otherwise>t.created_at</otherwise>
      </choose>
      <choose><when test="sortOrder == 'asc'">ASC</when><otherwise>DESC</otherwise></choose>,
      t.id <choose><when test="sortOrder == 'asc'">ASC</when><otherwise>DESC</otherwise></choose>
      LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<Map<String,Object>> taskItems(
      @Param("tenantId")Long tenantId,
      @Param("operation")String operation,
      @Param("state")String state,
      @Param("commandId")String commandId,
      @Param("instanceNo")String instanceNo,
      @Param("startedAt")LocalDateTime startedAt,
      @Param("endedAt")LocalDateTime endedAt,
      @Param("sort")String sort,
      @Param("sortOrder")String sortOrder,
      @Param("offset")long offset,
      @Param("size")int size);

  @Select("""
      <script>
      SELECT COUNT(*)
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      WHERE (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
      <if test="operation != null">AND t.operation_type=#{operation}</if>
      <if test="state != null">AND t.state=#{state}</if>
      <if test="commandId != null">AND t.command_id=#{commandId}</if>
      <if test="instanceNo != null">AND i.instance_no=#{instanceNo}</if>
      <if test="startedAt != null">AND t.created_at&gt;=#{startedAt}</if>
      <if test="endedAt != null">AND t.created_at&lt;=#{endedAt}</if>
      </script>
      """)
  long taskCount(
      @Param("tenantId")Long tenantId,
      @Param("operation")String operation,
      @Param("state")String state,
      @Param("commandId")String commandId,
      @Param("instanceNo")String instanceNo,
      @Param("startedAt")LocalDateTime startedAt,
      @Param("endedAt")LocalDateTime endedAt);

  @Select("""
      SELECT t.id,t.task_no taskNo,t.command_id commandId,t.tenant_id tenantId,
        tn.name tenantName,t.instance_id instanceId,i.instance_no instanceNo,
        i.name instanceName,i.status instanceStatus,i.engine_instance_id engineInstanceId,
        t.operation_type operation,t.previous_instance_status previousInstanceStatus,
        t.target_instance_status targetInstanceStatus,t.scenario,t.state,
        t.retry_count retryCount,t.manual_retry_count manualRetryCount,
        t.next_retry_at nextRetryAt,t.deadline_at deadlineAt,t.last_error lastError,
        t.actor_id actorId,t.message_id messageId,t.engine_event_id engineEventId,
        t.source_task_id sourceTaskId,t.accepted_at acceptedAt,t.finished_at finishedAt,
        t.created_at createdAt,t.updated_at updatedAt,
        o.id outboxId,o.event_id outboxEventId,o.state outboxState,
        o.retry_count outboxRetryCount,o.next_retry_at outboxNextRetryAt,
        o.last_error outboxLastError,o.created_at outboxCreatedAt,
        o.updated_at outboxUpdatedAt,o.published_at outboxPublishedAt
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      JOIN tenant tn ON tn.id=t.tenant_id
      LEFT JOIN outbox_event o ON o.id=(
        SELECT MAX(o2.id) FROM outbox_event o2 WHERE o2.task_id=t.id)
      WHERE t.id=#{taskId} AND (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
      """)
  Map<String,Object> taskDetail(@Param("taskId")long taskId,@Param("tenantId")Long tenantId);

  @Select("""
      SELECT t.id,t.command_id commandId,t.tenant_id tenantId,t.instance_id instanceId,
        t.operation_type operation,t.state,i.status instanceStatus,
        i.active_task_id activeTaskId
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      WHERE t.id=#{taskId} AND (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
      """)
  Map<String,Object> recoveryTask(@Param("taskId")long taskId,@Param("tenantId")Long tenantId);

  @Select("""
      SELECT t.id,t.command_id commandId,t.tenant_id tenantId,t.instance_id instanceId,
        t.operation_type operation,t.state,i.status instanceStatus,
        i.active_task_id activeTaskId
      FROM async_task t
      JOIN compute_instance i ON i.id=t.instance_id
      WHERE t.id=#{taskId} AND (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
      FOR UPDATE
      """)
  Map<String,Object> recoveryTaskForUpdate(
      @Param("taskId")long taskId,@Param("tenantId")Long tenantId);

  @Select("""
      SELECT t.id taskId,t.instance_id instanceId,t.operation_type operation,
        t.retry_count retryCount,o.id outboxId
      FROM async_task t
      JOIN outbox_event o ON o.task_id=t.id
      WHERE t.state='WAITING_CALLBACK' AND t.deadline_at<=NOW(3)
        AND o.state='WAITING_CALLBACK'
      ORDER BY t.id
      LIMIT #{limit}
      FOR UPDATE SKIP LOCKED
      """)
  List<Map<String,Object>> claimExpiredCallbacks(@Param("limit")int limit);
}
