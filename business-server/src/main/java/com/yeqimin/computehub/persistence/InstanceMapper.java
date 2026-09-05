package com.yeqimin.computehub.persistence;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface InstanceMapper {
  @Insert("INSERT INTO compute_order(order_no,tenant_id,product_id,product_snapshot,quantity,amount_cent,status,created_by) VALUES(#{orderNo},#{tenantId},#{productId},CAST(#{productSnapshot} AS JSON),#{quantity},#{amountCent},'FROZEN',#{createdBy})") @Options(useGeneratedKeys=true,keyProperty="id") int insertOrder(Map<String,Object> row);
  @Insert("INSERT INTO compute_instance(instance_no,order_id,tenant_id,product_id,cluster_id,name,scenario,status) VALUES(#{instanceNo},#{orderId},#{tenantId},#{productId},#{clusterId},#{name},#{scenario},'REQUESTED')") @Options(useGeneratedKeys=true,keyProperty="id") int insertInstance(Map<String,Object> row);
  @Insert("""
      INSERT INTO async_task(
        task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,
        operation_type,previous_instance_status,target_instance_status,scenario,
        actor_id,message_id,accepted_at)
      VALUES(#{taskNo},#{commandId},#{tenantId},#{instanceId},'PENDING',0,NOW(3),
        #{operation},#{previousStatus},#{targetStatus},#{scenario},#{actorId},
        #{messageId},NOW(3))
      """)
  @Options(useGeneratedKeys=true,keyProperty="id")
  int insertTask(Map<String,Object> row);

  @Insert("""
      INSERT INTO outbox_event(
        event_id,aggregate_type,aggregate_id,event_type,payload,state,retry_count,
        next_retry_at,task_id,command_id,message_id)
      VALUES(#{messageId},'INSTANCE',#{instanceId},'INSTANCE_COMMAND',
        CAST(#{payload} AS JSON),'READY',0,NOW(3),#{taskId},#{commandId},#{messageId})
      """)
  int insertOutbox(Map<String,Object> row);

  @Update("""
      UPDATE compute_instance
      SET active_task_id=#{taskId},status=#{status},version=version+1
      WHERE id=#{instanceId} AND active_task_id IS NULL
      """)
  int setActiveTask(
      @Param("instanceId")long instanceId,
      @Param("taskId")long taskId,
      @Param("status")String status);

  @Select("""
      SELECT i.id,i.instance_no instanceNo,i.tenant_id tenantId,i.name,i.scenario,
        i.engine_instance_id engineInstanceId,i.status,i.active_task_id activeTaskId,
        i.deleted_at deletedAt,p.name productName,p.sku,p.gpu_model gpuModel,
        p.gpu_count gpuCount,c.name clusterName,c.code clusterCode,o.order_no orderNo,
        o.amount_cent amountCent,i.created_at createdAt,i.updated_at updatedAt,
        t.id taskId,t.task_no taskNo,t.command_id commandId,t.state taskState,
        t.operation_type operation,t.retry_count retryCount,t.last_error lastError
      FROM compute_instance i
      JOIN compute_order o ON o.id=i.order_id
      JOIN compute_product p ON p.id=i.product_id
      JOIN compute_cluster c ON c.id=i.cluster_id
      LEFT JOIN async_task t ON t.id=COALESCE(i.active_task_id,
        (SELECT MAX(t2.id) FROM async_task t2 WHERE t2.instance_id=i.id))
      WHERE i.id=#{id}
      """)
  Map<String,Object> instance(long id);

  @Select("""
      <script>
      SELECT i.id,i.instance_no instanceNo,i.tenant_id tenantId,i.name,i.scenario,
        i.engine_instance_id engineInstanceId,i.status,i.active_task_id activeTaskId,
        i.deleted_at deletedAt,p.name productName,p.gpu_model gpuModel,
        p.gpu_count gpuCount,c.name clusterName,c.code clusterCode,o.order_no orderNo,
        o.amount_cent amountCent,i.created_at createdAt,i.updated_at updatedAt,
        t.id taskId,t.task_no taskNo,t.command_id commandId,t.state taskState,
        t.operation_type operation,t.retry_count retryCount,t.last_error lastError
      FROM compute_instance i
      JOIN compute_order o ON o.id=i.order_id
      JOIN compute_product p ON p.id=i.product_id
      JOIN compute_cluster c ON c.id=i.cluster_id
      LEFT JOIN async_task t ON t.id=COALESCE(i.active_task_id,
        (SELECT MAX(t2.id) FROM async_task t2 WHERE t2.instance_id=i.id))
      WHERE (#{tenantId} IS NULL OR i.tenant_id=#{tenantId})
      <if test="keyword != null">
        AND (i.name LIKE CONCAT('%',#{keyword},'%')
          OR i.instance_no LIKE CONCAT('%',#{keyword},'%'))
      </if>
      <if test="productId != null">AND i.product_id=#{productId}</if>
      <if test="clusterId != null">AND i.cluster_id=#{clusterId}</if>
      <choose>
        <when test="status != null">AND i.status=#{status}</when>
        <otherwise>AND i.status&lt;&gt;'DELETED'</otherwise>
      </choose>
      <if test="startTime != null">AND i.created_at&gt;=#{startTime}</if>
      <if test="endTime != null">AND i.created_at&lt;=#{endTime}</if>
      ORDER BY
      <choose>
        <when test="sort == 'updatedAt'">i.updated_at</when>
        <when test="sort == 'name'">i.name</when>
        <when test="sort == 'status'">i.status</when>
        <otherwise>i.created_at</otherwise>
      </choose>
      <choose>
        <when test="sortOrder == 'asc'">ASC</when>
        <otherwise>DESC</otherwise>
      </choose>,
      i.id
      <choose>
        <when test="sortOrder == 'asc'">ASC</when>
        <otherwise>DESC</otherwise>
      </choose>
      LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<Map<String,Object>> instances(
      @Param("tenantId")Long tenantId,
      @Param("keyword")String keyword,
      @Param("productId")Long productId,
      @Param("clusterId")Long clusterId,
      @Param("status")String status,
      @Param("startTime")LocalDateTime startTime,
      @Param("endTime")LocalDateTime endTime,
      @Param("sort")String sort,
      @Param("sortOrder")String sortOrder,
      @Param("offset")int offset,
      @Param("size")int size);

  @Select("""
      <script>
      SELECT COUNT(*) FROM compute_instance i
      WHERE (#{tenantId} IS NULL OR i.tenant_id=#{tenantId})
      <if test="keyword != null">
        AND (i.name LIKE CONCAT('%',#{keyword},'%')
          OR i.instance_no LIKE CONCAT('%',#{keyword},'%'))
      </if>
      <if test="productId != null">AND i.product_id=#{productId}</if>
      <if test="clusterId != null">AND i.cluster_id=#{clusterId}</if>
      <choose>
        <when test="status != null">AND i.status=#{status}</when>
        <otherwise>AND i.status&lt;&gt;'DELETED'</otherwise>
      </choose>
      <if test="startTime != null">AND i.created_at&gt;=#{startTime}</if>
      <if test="endTime != null">AND i.created_at&lt;=#{endTime}</if>
      </script>
      """)
  long instanceCount(
      @Param("tenantId")Long tenantId,
      @Param("keyword")String keyword,
      @Param("productId")Long productId,
      @Param("clusterId")Long clusterId,
      @Param("status")String status,
      @Param("startTime")LocalDateTime startTime,
      @Param("endTime")LocalDateTime endTime);
  @Select("SELECT t.id taskId,t.task_no taskNo,t.command_id commandId,t.tenant_id tenantId,t.instance_id instanceId,t.state taskState,t.retry_count retryCount,t.deadline_at deadlineAt,t.last_error,i.status instanceStatus,i.scenario,i.instance_no instanceNo,i.order_id orderId,o.order_no orderNo,o.amount_cent amountCent FROM async_task t JOIN compute_instance i ON i.id=t.instance_id JOIN compute_order o ON o.id=i.order_id WHERE t.command_id=#{commandId}") Map<String,Object> taskByCommand(String commandId);
  @Select("SELECT t.id taskId,t.task_no taskNo,t.command_id commandId,t.tenant_id tenantId,t.instance_id instanceId,t.state taskState,t.retry_count retryCount,t.deadline_at deadlineAt,t.last_error,i.status instanceStatus,i.scenario,i.instance_no instanceNo,i.order_id orderId,o.order_no orderNo,o.amount_cent amountCent FROM async_task t JOIN compute_instance i ON i.id=t.instance_id JOIN compute_order o ON o.id=i.order_id WHERE t.id=#{taskId}") Map<String,Object> task(long taskId);
  @Update("UPDATE compute_instance SET status=#{status},engine_instance_id=COALESCE(#{engineId},engine_instance_id) WHERE id=#{id}") int updateInstance(@Param("id")long id,@Param("status")String status,@Param("engineId")String engineId);
  @Update("UPDATE compute_order SET status=#{status} WHERE id=(SELECT order_id FROM compute_instance WHERE id=#{instanceId})") int updateOrderByInstance(@Param("instanceId")long instanceId,@Param("status")String status);
  @Update("UPDATE async_task SET state=#{state},last_error=#{error},version=version+1 WHERE id=#{id}") int updateTask(@Param("id")long id,@Param("state")String state,@Param("error")String error);
  @Insert("INSERT IGNORE INTO inbox_event(engine_event_id,command_id,event_type,payload_hash) VALUES(#{eventId},#{commandId},#{eventType},#{payloadHash})") int insertInbox(Map<String,Object> row);
  @Select("SELECT COUNT(*) FROM compute_instance WHERE (#{tenantId} IS NULL OR tenant_id=#{tenantId}) AND status='RUNNING'") long runningCount(Long tenantId);
}
