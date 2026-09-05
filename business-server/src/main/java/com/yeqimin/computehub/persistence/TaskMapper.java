package com.yeqimin.computehub.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface TaskMapper {
  @Select("SELECT o.id,o.event_id eventId,o.aggregate_id instanceId,o.payload,o.retry_count retryCount,t.id taskId,t.command_id commandId,t.tenant_id tenantId,t.retry_count taskRetryCount,i.instance_no instanceNo,i.product_id productId,i.scenario,c.code clusterCode FROM outbox_event o JOIN async_task t ON t.instance_id=o.aggregate_id JOIN compute_instance i ON i.id=o.aggregate_id JOIN compute_cluster c ON c.id=i.cluster_id WHERE o.event_type='CREATE_INSTANCE' AND o.state='READY' AND o.next_retry_at<=NOW(3) ORDER BY o.id LIMIT 1 FOR UPDATE SKIP LOCKED") Map<String,Object> nextOutbox();
  @Update("UPDATE outbox_event SET state='WAITING_CALLBACK',last_error=NULL WHERE id=#{id}") int outboxWaiting(long id);
  @Update("UPDATE async_task SET state='WAITING_CALLBACK',deadline_at=DATE_ADD(NOW(3),INTERVAL 3 SECOND),last_error=NULL,version=version+1 WHERE id=#{id}") int taskWaiting(long id);
  @Update("UPDATE compute_instance SET status='DISPATCHING' WHERE id=#{id} AND status IN ('REQUESTED','UNKNOWN','DISPATCHING')") int dispatching(long id);
  @Update("UPDATE outbox_event SET state=#{state},retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error=#{error} WHERE id=#{id}") int outboxFailure(@Param("id")long id,@Param("state")String state,@Param("delay")int delay,@Param("error")String error);
  @Update("UPDATE async_task SET state=#{state},retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error=#{error},version=version+1 WHERE id=#{id}") int taskFailure(@Param("id")long id,@Param("state")String state,@Param("delay")int delay,@Param("error")String error);
  @Select("SELECT t.id taskId,t.instance_id instanceId,t.retry_count retryCount,o.id outboxId FROM async_task t JOIN outbox_event o ON o.aggregate_id=t.instance_id WHERE t.state='WAITING_CALLBACK' AND t.deadline_at<=NOW(3) ORDER BY t.id LIMIT 20") List<Map<String,Object>> expiredTasks();
  @Update("UPDATE async_task SET state='READY',retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error='等待回调超时',version=version+1 WHERE id=#{taskId} AND state='WAITING_CALLBACK'") int retryExpired(@Param("taskId")long taskId,@Param("delay")int delay);
  @Update("UPDATE outbox_event SET state='READY',retry_count=retry_count+1,next_retry_at=DATE_ADD(NOW(3),INTERVAL #{delay} SECOND),last_error='等待回调超时' WHERE id=#{outboxId}") int retryOutbox(@Param("outboxId")long outboxId,@Param("delay")int delay);
  @Update("UPDATE async_task SET state='UNKNOWN',last_error='重试耗尽，等待人工对账',version=version+1 WHERE id=#{taskId}") int taskUnknown(long taskId);
  @Update("UPDATE outbox_event SET state='DEAD',last_error='重试耗尽，等待人工对账' WHERE id=#{outboxId}") int outboxDead(long outboxId);
  @Update("UPDATE compute_instance SET status='UNKNOWN' WHERE id=#{instanceId} AND status='DISPATCHING'") int instanceUnknown(long instanceId);
  @Update("UPDATE async_task SET state='READY',retry_count=0,next_retry_at=NOW(3),last_error=NULL WHERE id=#{taskId} AND state='UNKNOWN'") int manualRetry(long taskId);
  @Update("UPDATE outbox_event SET state='READY',retry_count=0,next_retry_at=NOW(3),last_error=NULL WHERE aggregate_id=#{instanceId}") int manualOutbox(long instanceId);
  @Update("UPDATE outbox_event SET state='DONE',last_error=NULL WHERE aggregate_id=#{instanceId}") int outboxDone(long instanceId);
}
