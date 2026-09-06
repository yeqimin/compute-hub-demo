package com.yeqimin.computehub.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface IdempotencyMapper {
  @Insert("INSERT IGNORE INTO idempotency_record(actor_id,idempotency_key,request_hash,resource_type,status) VALUES(#{actorId},#{key},#{hash},#{type},'PROCESSING')")
  int tryStart(@Param("actorId") long actorId,@Param("key") String key,@Param("hash") String hash,@Param("type") String type);
  @Insert("INSERT IGNORE INTO idempotency_record(actor_id,idempotency_key,request_hash,resource_type,status,processing_token,locked_at) VALUES(#{actorId},#{key},#{hash},'INSTANCE_BATCH','PROCESSING',#{token},NOW(3))")
  int tryStartBatch(
      @Param("actorId") long actorId,
      @Param("key") String key,
      @Param("hash") String hash,
      @Param("token") String token);
  @Update("UPDATE idempotency_record SET processing_token=#{token},locked_at=NOW(3) WHERE actor_id=#{actorId} AND idempotency_key=#{key} AND request_hash=#{hash} AND resource_type='INSTANCE_BATCH' AND status='PROCESSING' AND (locked_at IS NULL OR locked_at<=TIMESTAMPADD(SECOND,-#{staleSeconds},NOW(3)))")
  int tryTakeoverBatch(
      @Param("actorId") long actorId,
      @Param("key") String key,
      @Param("hash") String hash,
      @Param("token") String token,
      @Param("staleSeconds") int staleSeconds);
  @Update("UPDATE idempotency_record SET locked_at=NOW(3) WHERE actor_id=#{actorId} AND idempotency_key=#{key} AND status='PROCESSING' AND processing_token=#{token}")
  int renewBatchLease(
      @Param("actorId") long actorId,
      @Param("key") String key,
      @Param("token") String token);
  @Select("SELECT actor_id actorId,idempotency_key idempotencyKey,request_hash requestHash,resource_type resourceType,resource_id resourceId,response_body responseBody,status,processing_token processingToken,locked_at lockedAt FROM idempotency_record WHERE actor_id=#{actorId} AND idempotency_key=#{key}")
  Map<String,Object> find(@Param("actorId") long actorId,@Param("key") String key);
  @Update("UPDATE idempotency_record SET resource_id=#{resourceId},response_body=CAST(#{responseBody} AS JSON),status='COMPLETED' WHERE actor_id=#{actorId} AND idempotency_key=#{key}")
  int complete(@Param("actorId") long actorId,@Param("key") String key,@Param("resourceId") long resourceId,@Param("responseBody") String responseBody);
  @Update("UPDATE idempotency_record SET resource_id=#{resourceId},response_body=CAST(#{responseBody} AS JSON),status='COMPLETED',locked_at=NOW(3) WHERE actor_id=#{actorId} AND idempotency_key=#{key} AND status='PROCESSING' AND processing_token=#{token}")
  int completeBatch(
      @Param("actorId") long actorId,
      @Param("key") String key,
      @Param("token") String token,
      @Param("resourceId") long resourceId,
      @Param("responseBody") String responseBody);
}
