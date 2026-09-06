package com.yeqimin.computehub.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.persistence.*;
import com.yeqimin.computehub.security.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class InstanceService {
  private final InstanceTxService tx; private final InstanceMapper mapper; private final IdempotencyMapper idem; private final StringRedisTemplate redis; private final ObjectMapper json;
  public InstanceService(InstanceTxService tx,InstanceMapper mapper,IdempotencyMapper idem,StringRedisTemplate redis,ObjectMapper json){this.tx=tx;this.mapper=mapper;this.idem=idem;this.redis=redis;this.json=json;}
  public Map<String,Object> create(String key,CreateInstanceRequest request){
    if(key==null||key.isBlank())throw BusinessException.badRequest("缺少 Idempotency-Key 请求头");
    UserPrincipal user=CurrentUser.get();long tenantId;
    try{tenantId=CurrentUser.scopedTenant(request.tenantId());}catch(IllegalArgumentException e){throw BusinessException.badRequest(e.getMessage());}
    String body=write(request),hash=RequestFingerprint.of(body),lock="idem:"+user.id()+":"+key,token=UUID.randomUUID().toString();
    boolean acquired=false;
    try{acquired=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,token,Duration.ofSeconds(10)));}catch(Exception ignored){acquired=true;}
    if(!acquired)return awaitReplay(user.id(),key,hash);
    try{return tx.create(user.id(),tenantId,key,hash,request);}finally{try{if(token.equals(redis.opsForValue().get(lock)))redis.delete(lock);}catch(Exception ignored){}}
  }
  public Map<String,Object> get(long id){Map<String,Object> value=mapper.instance(id);if(value==null)throw BusinessException.notFound("实例不存在");scope(value);return value;}
  public Map<String,Object> list(String status,int page,int size){return list(null,null,null,null,status,null,null,"createdAt","desc",page,size);}
  public Map<String,Object> list(
      String keyword,Long requestedTenantId,Long productId,Long clusterId,String status,
      LocalDateTime startTime,LocalDateTime endTime,String sort,String order,int page,int size){
    UserPrincipal user=CurrentUser.get();
    Long tenant=user.platformAdmin()?requestedTenantId:user.tenantId();
    int safeSize=Math.min(Math.max(size,1),100),safePage=Math.max(page,1);
    String safeSort=Set.of("createdAt","updatedAt","name","status").contains(sort)?sort:"createdAt";
    String safeOrder="asc".equalsIgnoreCase(order)?"asc":"desc";
    var items=mapper.instances(tenant,blankToNull(keyword),productId,clusterId,blankToNull(status),startTime,endTime,safeSort,safeOrder,(safePage-1)*safeSize,safeSize);
    long total=mapper.instanceCount(tenant,blankToNull(keyword),productId,clusterId,blankToNull(status),startTime,endTime);
    return Map.of("items",items,"total",total,"page",safePage,"size",safeSize);
  }
  private Map<String,Object> awaitReplay(long actor,String key,String hash){for(int i=0;i<20;i++){Map<String,Object> old=idem.find(actor,key);if(old!=null&&!hash.equals(old.get("requestHash")))throw BusinessException.conflict("Idempotency-Key 已用于不同请求");if(old!=null&&"COMPLETED".equals(old.get("status"))){try{return json.readValue(String.valueOf(old.get("responseBody")),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}throw BusinessException.conflict("相同请求正在处理中，请稍后重试");}
  private void scope(Map<String,Object> row){UserPrincipal u=CurrentUser.get();if(!u.platformAdmin()&&((Number)row.get("tenantId")).longValue()!=u.tenantId())throw BusinessException.notFound("实例不存在");}
  private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
