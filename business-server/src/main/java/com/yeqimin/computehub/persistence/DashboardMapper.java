package com.yeqimin.computehub.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.Select;

public interface DashboardMapper {
  @Select("SELECT (SELECT COUNT(*) FROM compute_cluster) clusterCount,(SELECT COUNT(*) FROM compute_node WHERE status='READY') readyNodeCount,(SELECT COALESCE(SUM(gpu_total),0) FROM compute_node) gpuTotal,(SELECT COALESCE(SUM(gpu_allocated),0) FROM compute_node) gpuAllocated,(SELECT COUNT(*) FROM compute_instance WHERE (#{tenantId} IS NULL OR tenant_id=#{tenantId}) AND status='RUNNING') runningInstances,(SELECT COALESCE(SUM(available_cent),0) FROM tenant_wallet WHERE (#{tenantId} IS NULL OR tenant_id=#{tenantId})) availableCent,(SELECT COALESCE(SUM(frozen_cent),0) FROM tenant_wallet WHERE (#{tenantId} IS NULL OR tenant_id=#{tenantId})) frozenCent")
  Map<String,Object> summary(Long tenantId);
}
