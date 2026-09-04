package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.domain.InstanceStateMachine;
import com.yeqimin.computehub.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
  private final InstanceMapper instances;private final BillingMapper billing;private final TaskMapper tasks;
  public SettlementService(InstanceMapper instances,BillingMapper billing,TaskMapper tasks){this.instances=instances;this.billing=billing;this.tasks=tasks;}
  @Transactional public Map<String,Object> settle(String eventId,String commandId,String status,String engineId,String message,String payloadHash){
    if(!Set.of("RUNNING","FAILED").contains(status))throw BusinessException.badRequest("不支持的引擎状态");
    Map<String,Object> event=new HashMap<>();event.put("eventId",eventId);event.put("commandId",commandId);event.put("eventType",status);event.put("payloadHash",payloadHash);if(instances.insertInbox(event)==0)return Map.of("duplicate",true);
    Map<String,Object> task=instances.taskByCommand(commandId);if(task==null)throw BusinessException.notFound("异步命令不存在");String current=String.valueOf(task.get("instanceStatus"));if(Set.of("RUNNING","FAILED").contains(current))return Map.of("duplicate",true);
    if(!InstanceStateMachine.canTransition(current,status))throw BusinessException.conflict("非法状态流转: "+current+" -> "+status);
    long tenant=num(task,"tenantId"),instance=num(task,"instanceId"),amount=num(task,"amountCent");Map<String,Object>wallet=billing.walletForUpdate(tenant);long available=num(wallet,"availableCent"),frozen=num(wallet,"frozenCent");if(frozen<amount)throw BusinessException.conflict("冻结余额不足，拒绝重复结算");
    long nextAvailable=available,nextFrozen=frozen-amount;String ledgerType="DEDUCT",remark="实例创建成功扣款";
    if("FAILED".equals(status)){nextAvailable=available+amount;ledgerType="UNFREEZE";remark="实例创建失败解冻";}
    if(billing.updateWallet(tenant,nextAvailable,nextFrozen)!=1)throw BusinessException.conflict("钱包结算失败");
    Map<String,Object> ledger=new HashMap<>();ledger.put("ledgerNo","LED-"+uuid());ledger.put("tenantId",tenant);ledger.put("bizNo",String.valueOf(task.get("orderNo")));ledger.put("type",ledgerType);ledger.put("deltaAvailable","FAILED".equals(status)?amount:0);ledger.put("deltaFrozen",-amount);ledger.put("availableAfter",nextAvailable);ledger.put("frozenAfter",nextFrozen);ledger.put("remark",message==null?remark:remark+": "+message);billing.insertLedger(ledger);
    instances.updateInstance(instance,status,engineId);instances.updateOrderByInstance(instance,"RUNNING".equals(status)?"COMPLETED":"FAILED");instances.updateTask(num(task,"taskId"),"RUNNING".equals(status)?"SUCCESS":"FAILED",message);tasks.outboxDone(instance);
    return Map.of("duplicate",false,"instanceId",instance,"status",status);
  }
  private static long num(Map<String,Object>m,String k){return((Number)m.get(k)).longValue();}
  private static String uuid(){return UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();}
}
