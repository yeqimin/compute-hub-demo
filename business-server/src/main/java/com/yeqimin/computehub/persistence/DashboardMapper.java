package com.yeqimin.computehub.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DashboardMapper {
  @Select("""
      SELECT
        CASE WHEN #{tenantId} IS NULL THEN (SELECT COUNT(*) FROM compute_cluster)
          ELSE (SELECT COUNT(DISTINCT i.cluster_id) FROM compute_instance i WHERE i.tenant_id=#{tenantId} AND i.status<>'DELETED') END clusterCount,
        CASE WHEN #{tenantId} IS NULL THEN (SELECT COUNT(*) FROM compute_node n WHERE n.status='READY')
          ELSE (SELECT COUNT(*) FROM compute_node n WHERE n.status='READY' AND EXISTS (SELECT 1 FROM compute_instance i WHERE i.cluster_id=n.cluster_id AND i.tenant_id=#{tenantId} AND i.status<>'DELETED')) END readyNodeCount,
        CASE WHEN #{tenantId} IS NULL THEN (SELECT COUNT(*) FROM compute_node n WHERE n.status<>'READY')
          ELSE (SELECT COUNT(*) FROM compute_node n WHERE n.status<>'READY' AND EXISTS (SELECT 1 FROM compute_instance i WHERE i.cluster_id=n.cluster_id AND i.tenant_id=#{tenantId} AND i.status<>'DELETED')) END unhealthyNodeCount,
        CASE WHEN #{tenantId} IS NULL THEN (SELECT COALESCE(SUM(n.gpu_total),0) FROM compute_node n)
          ELSE (SELECT COALESCE(SUM(p.gpu_count),0) FROM compute_instance i JOIN compute_product p ON p.id=i.product_id WHERE i.tenant_id=#{tenantId} AND i.status<>'DELETED') END gpuTotal,
        CASE WHEN #{tenantId} IS NULL THEN (SELECT COALESCE(SUM(n.gpu_allocated),0) FROM compute_node n)
          ELSE (SELECT COALESCE(SUM(p.gpu_count),0) FROM compute_instance i JOIN compute_product p ON p.id=i.product_id WHERE i.tenant_id=#{tenantId} AND i.status='RUNNING') END gpuAllocated,
        (SELECT COUNT(*) FROM compute_instance i WHERE (#{tenantId} IS NULL OR i.tenant_id=#{tenantId}) AND i.status='RUNNING') runningInstances,
        (SELECT COUNT(*) FROM compute_instance i WHERE (#{tenantId} IS NULL OR i.tenant_id=#{tenantId}) AND i.status IN ('UNKNOWN','FAILED')) abnormalInstances,
        (SELECT COALESCE(SUM(w.available_cent),0) FROM tenant_wallet w WHERE (#{tenantId} IS NULL OR w.tenant_id=#{tenantId})) availableCent,
        (SELECT COALESCE(SUM(w.frozen_cent),0) FROM tenant_wallet w WHERE (#{tenantId} IS NULL OR w.tenant_id=#{tenantId})) frozenCent
      """)
  Map<String,Object> summary(@Param("tenantId") Long tenantId);

  @Select("""
      SELECT COUNT(CASE WHEN t.state='SUCCEEDED' THEN 1 END) successfulTasks,
        COUNT(CASE WHEN t.state IN ('FAILED','UNKNOWN','DEAD') THEN 1 END) abnormalTasks,
        COUNT(*) totalTasks
      FROM async_task t
      WHERE (#{tenantId} IS NULL OR t.tenant_id=#{tenantId})
        AND t.created_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR)
      """)
  Map<String,Object> taskSummary(@Param("tenantId") Long tenantId);

  @Select("""
      SELECT i.status status,COUNT(*) value
      FROM compute_instance i
      WHERE (#{tenantId} IS NULL OR i.tenant_id=#{tenantId}) AND i.status<>'DELETED'
      GROUP BY i.status ORDER BY i.status
      """)
  List<Map<String,Object>> instanceDistribution(@Param("tenantId") Long tenantId);

  @Select("""
      SELECT c.id clusterId,c.code clusterCode,c.name clusterName,c.region clusterRegion,c.status clusterStatus,
        n.id nodeId,n.name nodeName,n.status nodeStatus,n.gpu_model gpuModel,n.gpu_total gpuTotal,n.gpu_allocated gpuAllocated
      FROM compute_cluster c JOIN compute_node n ON n.cluster_id=c.id
      WHERE #{tenantId} IS NULL OR EXISTS (
        SELECT 1 FROM compute_instance i
        WHERE i.cluster_id=c.id AND i.tenant_id=#{tenantId} AND i.status<>'DELETED')
      ORDER BY c.id,n.id
      """)
  List<Map<String,Object>> topology(@Param("tenantId") Long tenantId);
}
