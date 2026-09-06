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
  @Update("UPDATE tenant_wallet SET available_cent=available_cent+#{released},frozen_cent=frozen_cent-#{amount},version=version+1 WHERE tenant_id=#{tenantId} AND frozen_cent>=#{amount}")
  int settleFrozen(@Param("tenantId")long tenantId,@Param("amount")long amount,@Param("released")long released);
  @Insert("INSERT IGNORE INTO wallet_ledger(ledger_no,tenant_id,biz_no,type,delta_available_cent,delta_frozen_cent,available_after_cent,frozen_after_cent,remark) VALUES(#{ledgerNo},#{tenantId},#{bizNo},#{type},#{deltaAvailable},#{deltaFrozen},#{availableAfter},#{frozenAfter},#{remark})")
  int insertLedger(Map<String,Object> row);
  @Select("<script>SELECT l.id,l.ledger_no ledgerNo,l.tenant_id tenantId,l.biz_no bizNo,l.type,l.delta_available_cent deltaAvailableCent,l.delta_frozen_cent deltaFrozenCent,l.available_after_cent availableAfterCent,l.frozen_after_cent frozenAfterCent,l.remark,l.created_at createdAt,i.id instanceId,i.instance_no instanceNo FROM wallet_ledger l LEFT JOIN compute_order o ON o.tenant_id=l.tenant_id AND o.order_no=l.biz_no LEFT JOIN compute_instance i ON i.order_id=o.id WHERE l.tenant_id=#{tenantId}<if test='type != null'> AND l.type=#{type}</if><if test='bizNo != null'> AND l.biz_no LIKE CONCAT('%',#{bizNo},'%')</if><if test='startTime != null'> AND l.created_at &gt;= #{startTime}</if><if test='endTime != null'> AND l.created_at &lt;= #{endTime}</if> ORDER BY l.created_at DESC,l.id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<Map<String,Object>> ledgers(@Param("tenantId")long tenantId,@Param("type")String type,@Param("bizNo")String bizNo,@Param("startTime")java.time.LocalDateTime startTime,@Param("endTime")java.time.LocalDateTime endTime,@Param("offset")long offset,@Param("size")int size);
  @Select("<script>SELECT COUNT(*) FROM wallet_ledger l WHERE l.tenant_id=#{tenantId}<if test='type != null'> AND l.type=#{type}</if><if test='bizNo != null'> AND l.biz_no LIKE CONCAT('%',#{bizNo},'%')</if><if test='startTime != null'> AND l.created_at &gt;= #{startTime}</if><if test='endTime != null'> AND l.created_at &lt;= #{endTime}</if></script>") long ledgerCount(@Param("tenantId")long tenantId,@Param("type")String type,@Param("bizNo")String bizNo,@Param("startTime")java.time.LocalDateTime startTime,@Param("endTime")java.time.LocalDateTime endTime);
}
