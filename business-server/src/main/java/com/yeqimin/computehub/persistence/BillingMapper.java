package com.yeqimin.computehub.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface BillingMapper {
  @Select("SELECT tenant_id tenantId,available_cent availableCent,frozen_cent frozenCent,version,updated_at updatedAt FROM tenant_wallet WHERE tenant_id=#{tenantId} FOR UPDATE")
  Map<String,Object> walletForUpdate(long tenantId);
  @Select("SELECT tenant_id tenantId,available_cent availableCent,frozen_cent frozenCent,version,updated_at updatedAt FROM tenant_wallet WHERE tenant_id=#{tenantId}")
  Map<String,Object> wallet(long tenantId);
  @Update("UPDATE tenant_wallet SET available_cent=#{available},frozen_cent=#{frozen},version=version+1 WHERE tenant_id=#{tenantId} AND #{available}>=0 AND #{frozen}>=0")
  int updateWallet(@Param("tenantId")long tenantId,@Param("available")long available,@Param("frozen")long frozen);
  @Insert("INSERT IGNORE INTO wallet_ledger(ledger_no,tenant_id,biz_no,type,delta_available_cent,delta_frozen_cent,available_after_cent,frozen_after_cent,remark) VALUES(#{ledgerNo},#{tenantId},#{bizNo},#{type},#{deltaAvailable},#{deltaFrozen},#{availableAfter},#{frozenAfter},#{remark})")
  int insertLedger(Map<String,Object> row);
  @Select("SELECT id,ledger_no ledgerNo,tenant_id tenantId,biz_no bizNo,type,delta_available_cent deltaAvailableCent,delta_frozen_cent deltaFrozenCent,available_after_cent availableAfterCent,frozen_after_cent frozenAfterCent,remark,created_at createdAt FROM wallet_ledger WHERE tenant_id=#{tenantId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
  List<Map<String,Object>> ledgers(@Param("tenantId")long tenantId,@Param("offset")int offset,@Param("size")int size);
  @Select("SELECT COUNT(*) FROM wallet_ledger WHERE tenant_id=#{tenantId}") long ledgerCount(long tenantId);
}
