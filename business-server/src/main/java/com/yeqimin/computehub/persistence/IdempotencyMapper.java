package com.yeqimin.computehub.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface IdempotencyMapper {
  @Insert("INSERT IGNORE INTO idempotency_record(actor_id,idempotency_key,request_hash,resource_type,status) VALUES(#{actorId},#{key},#{hash},#{type},'PROCESSING')")
  int tryStart(@Param("actorId") long actorId,@Param("key") String key,@Param("hash") String hash,@Param("type") String type);
  @Select("SELECT actor_id actorId,idempotency_key idempotencyKey,request_hash requestHash,resource_type resourceType,resource_id resourceId,response_body responseBody,status FROM idempotency_record WHERE actor_id=#{actorId} AND idempotency_key=#{key}")
  Map<String,Object> find(@Param("actorId") long actorId,@Param("key") String key);
  @Update("UPDATE idempotency_record SET resource_id=#{resourceId},response_body=CAST(#{responseBody} AS JSON),status='COMPLETED' WHERE actor_id=#{actorId} AND idempotency_key=#{key}")
  int complete(@Param("actorId") long actorId,@Param("key") String key,@Param("resourceId") long resourceId,@Param("responseBody") String responseBody);
}
