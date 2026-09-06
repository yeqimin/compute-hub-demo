package com.yeqimin.computehub.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.common.*;
import com.yeqimin.computehub.persistence.*;
import com.yeqimin.computehub.security.*;
import java.util.*;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
  private final BillingMapper billing;private final IdempotencyMapper idem;private final ObjectMapper json;
  public BillingService(BillingMapper billing,IdempotencyMapper idem,ObjectMapper json){this.billing=billing;this.idem=idem;this.json=json;}
  public Map<String,Object> wallet(Long requestedTenant){return billing.wallet(scope(requestedTenant));}
  public Map<String,Object> ledgers(Long requestedTenant,String type,String bizNo,LocalDateTime startTime,LocalDateTime endTime,int page,int size){
    if(startTime!=null&&endTime!=null&&startTime.isAfter(endTime))throw BusinessException.badRequest("开始时间不能晚于结束时间");
    long tenant=scope(requestedTenant);int safeSize=Math.min(Math.max(size,1),100),safePage=Math.max(page,1);long offset=((long)safePage-1)*safeSize;
    String safeType=type==null||type.isBlank()?null:(Set.of("RECHARGE","FREEZE","DEDUCT","UNFREEZE").contains(type)?type:null);
    if(type!=null&&!type.isBlank()&&safeType==null)throw BusinessException.badRequest("不支持的流水类型");
    return Map.of("items",billing.ledgers(tenant,safeType,blank(bizNo),startTime,endTime,offset,safeSize),"total",billing.ledgerCount(tenant,safeType,blank(bizNo),startTime,endTime),"page",safePage,"size",safeSize);
  }
  @Transactional public Map<String,Object> recharge(String key,Long requestedTenant,long amountCent,String remark){
    if(key==null||key.isBlank())throw BusinessException.badRequest("缺少 Idempotency-Key 请求头");if(amountCent<=0||amountCent>100_000_000)throw BusinessException.badRequest("充值金额必须在 1 到 100000000 分之间");
    UserPrincipal user=CurrentUser.get();long tenant=scope(requestedTenant);String request=write(Map.of("tenantId",tenant,"amountCent",amountCent,"remark",remark==null?"":remark)),hash=RequestFingerprint.of(request);
    if(idem.tryStart(user.id(),key,hash,"RECHARGE")==0){Map<String,Object> old=idem.find(user.id(),key);if(!hash.equals(old.get("requestHash")))throw BusinessException.conflict("Idempotency-Key 已用于不同请求");if("COMPLETED".equals(old.get("status")))return read(String.valueOf(old.get("responseBody")));throw BusinessException.conflict("充值请求正在处理中");}
    Map<String,Object> wallet=billing.walletForUpdate(tenant);if(wallet==null)throw BusinessException.notFound("租户钱包不存在");long before=((Number)wallet.get("availableCent")).longValue(),frozen=((Number)wallet.get("frozenCent")).longValue(),after=Math.addExact(before,amountCent);if(billing.updateWallet(tenant,after,frozen)!=1)throw BusinessException.conflict("钱包余额更新失败");
    String bizNo="RCH-"+uuid();Map<String,Object> ledger=new HashMap<>();ledger.put("ledgerNo","LED-"+uuid());ledger.put("tenantId",tenant);ledger.put("bizNo",bizNo);ledger.put("type","RECHARGE");ledger.put("deltaAvailable",amountCent);ledger.put("deltaFrozen",0);ledger.put("availableAfter",after);ledger.put("frozenAfter",frozen);ledger.put("remark",remark==null?"Demo 充值":remark);billing.insertLedger(ledger);
    Map<String,Object> result=billing.wallet(tenant);result.put("bizNo",bizNo);idem.complete(user.id(),key,tenant,write(result));return result;
  }
  private long scope(Long requested){try{return CurrentUser.scopedTenant(requested);}catch(IllegalArgumentException e){throw BusinessException.badRequest(e.getMessage());}}
  private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
  private static String uuid(){return UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
  private Map<String,Object> read(String value){try{return json.readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
}
