package com.yeqimin.computehub.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.audit.AuditService;
import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.domain.EngineScenario;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStateMachine;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.domain.TransitionPlan;
import com.yeqimin.computehub.persistence.*;
import com.yeqimin.computehub.realtime.RealtimeEventService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstanceTxService {
  private final CatalogMapper catalog;
  private final BillingMapper billing;
  private final InstanceMapper instances;
  private final IdempotencyMapper idempotency;
  private final AuditService audit;
  private final RealtimeEventService realtime;
  private final ObjectMapper json;
  private final String callbackUrl;

  public InstanceTxService(
      CatalogMapper catalog,
      BillingMapper billing,
      InstanceMapper instances,
      IdempotencyMapper idempotency,
      AuditService audit,
      RealtimeEventService realtime,
      ObjectMapper json,
      @Value("${compute-hub.callback-url}") String callbackUrl) {
    this.catalog = catalog;
    this.billing = billing;
    this.instances = instances;
    this.idempotency = idempotency;
    this.audit = audit;
    this.realtime = realtime;
    this.json = json;
    this.callbackUrl = callbackUrl;
  }

  @Transactional
  public Map<String,Object> create(long actorId,long tenantId,String key,String hash,CreateInstanceRequest request) {
    if(idempotency.tryStart(actorId,key,hash,"INSTANCE")==0) return replay(actorId,key,hash);
    Map<String,Object> product=catalog.product(request.productId());
    if(product==null || !truthy(product.get("enabled"))) throw BusinessException.badRequest("产品不存在或已停用");
    Map<String,Object> cluster=catalog.cluster(request.clusterId());
    if(cluster==null) throw BusinessException.badRequest("集群不存在");
    long amount=Math.multiplyExact(((Number)product.get("priceCent")).longValue(),request.quantity());
    Map<String,Object> wallet=billing.walletForUpdate(tenantId);
    if(wallet==null) throw BusinessException.notFound("租户钱包不存在");
    long available=num(wallet,"availableCent"), frozen=num(wallet,"frozenCent");
    if(available<amount) throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"INSUFFICIENT_BALANCE","钱包可用余额不足");
    long nextAvailable=available-amount,nextFrozen=frozen+amount;
    if(billing.updateWallet(tenantId,nextAvailable,nextFrozen)!=1) throw BusinessException.conflict("钱包余额更新失败");
    String scenarioName=request.scenario()==null||request.scenario().isBlank()?"SUCCESS":request.scenario();
    EngineScenario scenario=EngineScenario.valueOf(scenarioName);
    String orderNo=id("ORD"),instanceNo=id("INS"),commandId=id("CMD"),taskNo=id("TASK"),messageId=id("MSG");
    Map<String,Object> order=new HashMap<>();order.put("orderNo",orderNo);order.put("tenantId",tenantId);order.put("productId",request.productId());order.put("productSnapshot",write(product));order.put("quantity",request.quantity());order.put("amountCent",amount);order.put("createdBy",actorId);instances.insertOrder(order);
    Map<String,Object> instance=new HashMap<>();instance.put("instanceNo",instanceNo);instance.put("orderId",order.get("id"));instance.put("tenantId",tenantId);instance.put("productId",request.productId());instance.put("clusterId",request.clusterId());instance.put("name",request.name());instance.put("scenario",scenarioName);instances.insertInstance(instance);
    long instanceId=((Number)instance.get("id")).longValue();
    TransitionPlan plan=InstanceStateMachine.begin(InstanceStatus.REQUESTED,InstanceOperation.CREATE);
    Map<String,Object> task=new HashMap<>();task.put("taskNo",taskNo);task.put("commandId",commandId);task.put("tenantId",tenantId);task.put("instanceId",instanceId);task.put("operation",InstanceOperation.CREATE.name());task.put("previousStatus",plan.previous().name());task.put("targetStatus",plan.target().name());task.put("scenario",scenario.name());task.put("actorId",actorId);task.put("messageId",messageId);instances.insertTask(task);
    long taskId=((Number)task.get("id")).longValue();
    if(instances.setActiveTask(instanceId,taskId,plan.executing().name())!=1)throw BusinessException.conflict("实例存在活动任务");
    Map<String,Object> payload=new LinkedHashMap<>();payload.put("schemaVersion",1);payload.put("messageId",messageId);payload.put("taskId",taskId);payload.put("commandId",commandId);payload.put("instanceId",instanceId);payload.put("instanceNo",instanceNo);payload.put("engineInstanceId",null);payload.put("tenantId",tenantId);payload.put("productId",request.productId());payload.put("clusterCode",cluster.get("code"));payload.put("operation",InstanceOperation.CREATE.name());payload.put("scenario",scenario.name());payload.put("callbackUrl",callbackUrl);payload.put("attempt",0);
    Map<String,Object> outbox=new HashMap<>();outbox.put("messageId",messageId);outbox.put("taskId",taskId);outbox.put("commandId",commandId);outbox.put("instanceId",instanceId);outbox.put("payload",write(payload));instances.insertOutbox(outbox);
    insertLedger(tenantId,orderNo,"FREEZE",-amount,amount,nextAvailable,nextFrozen,"创建实例冻结余额");
    audit.appendAccepted(actorId,tenantId,instanceId,taskId,InstanceOperation.CREATE,plan);
    realtime.appendTaskChanged(tenantId,instanceId,taskId,InstanceOperation.CREATE,TaskState.PENDING);
    Map<String,Object> response=instances.instance(instanceId);idempotency.complete(actorId,key,instanceId,write(response));return response;
  }
  private Map<String,Object> replay(long actorId,String key,String hash){Map<String,Object> old=idempotency.find(actorId,key);if(old==null)throw BusinessException.conflict("请求正在处理中");if(!hash.equals(old.get("requestHash")))throw BusinessException.conflict("Idempotency-Key 已用于不同请求");if(!"COMPLETED".equals(old.get("status")))throw BusinessException.conflict("相同请求正在处理中，请稍后重试");try{return json.readValue(String.valueOf(old.get("responseBody")),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
  private void insertLedger(long tenantId,String bizNo,String type,long da,long df,long aa,long fa,String remark){Map<String,Object> row=new HashMap<>();row.put("ledgerNo",id("LED"));row.put("tenantId",tenantId);row.put("bizNo",bizNo);row.put("type",type);row.put("deltaAvailable",da);row.put("deltaFrozen",df);row.put("availableAfter",aa);row.put("frozenAfter",fa);row.put("remark",remark);billing.insertLedger(row);}
  private static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
  private static boolean truthy(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()==1);}
  private static String id(String prefix){return prefix+"-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
