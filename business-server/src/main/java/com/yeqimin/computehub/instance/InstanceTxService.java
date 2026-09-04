package com.yeqimin.computehub.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstanceTxService {
  private final CatalogMapper catalog; private final BillingMapper billing; private final InstanceMapper instances; private final IdempotencyMapper idempotency; private final ObjectMapper json;
  public InstanceTxService(CatalogMapper catalog,BillingMapper billing,InstanceMapper instances,IdempotencyMapper idempotency,ObjectMapper json){this.catalog=catalog;this.billing=billing;this.instances=instances;this.idempotency=idempotency;this.json=json;}

  @Transactional
  public Map<String,Object> create(long actorId,long tenantId,String key,String hash,CreateInstanceRequest request) {
    if(idempotency.tryStart(actorId,key,hash,"INSTANCE")==0) return replay(actorId,key,hash);
    Map<String,Object> product=catalog.product(request.productId());
    if(product==null || !truthy(product.get("enabled"))) throw BusinessException.badRequest("产品不存在或已停用");
    if(catalog.cluster(request.clusterId())==null) throw BusinessException.badRequest("集群不存在");
    long amount=Math.multiplyExact(((Number)product.get("priceCent")).longValue(),request.quantity());
    Map<String,Object> wallet=billing.walletForUpdate(tenantId);
    if(wallet==null) throw BusinessException.notFound("租户钱包不存在");
    long available=num(wallet,"availableCent"), frozen=num(wallet,"frozenCent");
    if(available<amount) throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"INSUFFICIENT_BALANCE","钱包可用余额不足");
    long nextAvailable=available-amount,nextFrozen=frozen+amount;
    if(billing.updateWallet(tenantId,nextAvailable,nextFrozen)!=1) throw BusinessException.conflict("钱包余额更新失败");
    String orderNo=id("ORD"),instanceNo=id("INS"),commandId=id("CMD"),taskNo=id("TASK"),eventId=id("EVT");
    Map<String,Object> order=new HashMap<>();order.put("orderNo",orderNo);order.put("tenantId",tenantId);order.put("productId",request.productId());order.put("productSnapshot",write(product));order.put("quantity",request.quantity());order.put("amountCent",amount);order.put("createdBy",actorId);instances.insertOrder(order);
    Map<String,Object> instance=new HashMap<>();instance.put("instanceNo",instanceNo);instance.put("orderId",order.get("id"));instance.put("tenantId",tenantId);instance.put("productId",request.productId());instance.put("clusterId",request.clusterId());instance.put("name",request.name());instance.put("scenario",request.scenario());instances.insertInstance(instance);
    long instanceId=((Number)instance.get("id")).longValue();
    Map<String,Object> task=new HashMap<>();task.put("taskNo",taskNo);task.put("commandId",commandId);task.put("tenantId",tenantId);task.put("instanceId",instanceId);instances.insertTask(task);
    Map<String,Object> payload=Map.of("commandId",commandId,"instanceNo",instanceNo,"tenantId",tenantId,"productId",request.productId(),"clusterId",request.clusterId(),"scenario",request.scenario());
    Map<String,Object> outbox=new HashMap<>();outbox.put("eventId",eventId);outbox.put("instanceId",instanceId);outbox.put("payload",write(payload));instances.insertOutbox(outbox);
    insertLedger(tenantId,orderNo,"FREEZE",-amount,amount,nextAvailable,nextFrozen,"创建实例冻结余额");
    Map<String,Object> response=instances.instance(instanceId);idempotency.complete(actorId,key,instanceId,write(response));return response;
  }
  private Map<String,Object> replay(long actorId,String key,String hash){Map<String,Object> old=idempotency.find(actorId,key);if(old==null)throw BusinessException.conflict("请求正在处理中");if(!hash.equals(old.get("requestHash")))throw BusinessException.conflict("Idempotency-Key 已用于不同请求");if(!"COMPLETED".equals(old.get("status")))throw BusinessException.conflict("相同请求正在处理中，请稍后重试");try{return json.readValue(String.valueOf(old.get("responseBody")),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
  private void insertLedger(long tenantId,String bizNo,String type,long da,long df,long aa,long fa,String remark){Map<String,Object> row=new HashMap<>();row.put("ledgerNo",id("LED"));row.put("tenantId",tenantId);row.put("bizNo",bizNo);row.put("type",type);row.put("deltaAvailable",da);row.put("deltaFrozen",df);row.put("availableAfter",aa);row.put("frozenAfter",fa);row.put("remark",remark);billing.insertLedger(row);}
  private static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
  private static boolean truthy(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()==1);}
  private static String id(String prefix){return prefix+"-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
