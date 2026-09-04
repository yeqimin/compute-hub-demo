package com.yeqimin.computehub.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface CatalogMapper {
  @Select("SELECT id,code,name,status,created_at createdAt FROM tenant ORDER BY id") List<Map<String,Object>> tenants();
  @Insert("INSERT INTO tenant(code,name,status) VALUES(#{code},#{name},'ACTIVE')") @Options(useGeneratedKeys=true,keyProperty="id") int insertTenant(Map<String,Object> row);
  @Insert("INSERT INTO tenant_wallet(tenant_id,available_cent,frozen_cent) VALUES(#{id},0,0)") int insertWallet(long id);

  @Select("SELECT u.id,u.tenant_id tenantId,t.name tenantName,u.username,u.display_name displayName,u.enabled,GROUP_CONCAT(r.code) roles FROM sys_user u LEFT JOIN tenant t ON t.id=u.tenant_id LEFT JOIN sys_user_role ur ON ur.user_id=u.id LEFT JOIN sys_role r ON r.id=ur.role_id WHERE (#{tenantId} IS NULL OR u.tenant_id=#{tenantId}) GROUP BY u.id ORDER BY u.id") List<Map<String,Object>> users(Long tenantId);
  @Select("SELECT id,code,name FROM sys_role ORDER BY id") List<Map<String,Object>> roles();
  @Insert("INSERT INTO sys_user(tenant_id,username,display_name,password_hash,enabled) VALUES(#{tenantId},#{username},#{displayName},#{passwordHash},1)") @Options(useGeneratedKeys=true,keyProperty="id") int insertUser(Map<String,Object> row);
  @Delete("DELETE FROM sys_user_role WHERE user_id=#{userId}") int clearUserRoles(long userId);
  @Insert("INSERT INTO sys_user_role(user_id,role_id) VALUES(#{userId},#{roleId})") int assignRole(@Param("userId") long userId,@Param("roleId") long roleId);

  @Select("SELECT id,sku,name,gpu_model gpuModel,gpu_count gpuCount,cpu_cores cpuCores,memory_gb memoryGb,price_cent priceCent,enabled,created_at createdAt FROM compute_product ORDER BY id") List<Map<String,Object>> products();
  @Select("SELECT id,sku,name,gpu_model gpuModel,gpu_count gpuCount,cpu_cores cpuCores,memory_gb memoryGb,price_cent priceCent,enabled FROM compute_product WHERE id=#{id}") Map<String,Object> product(long id);
  @Insert("INSERT INTO compute_product(sku,name,gpu_model,gpu_count,cpu_cores,memory_gb,price_cent,enabled) VALUES(#{sku},#{name},#{gpuModel},#{gpuCount},#{cpuCores},#{memoryGb},#{priceCent},#{enabled})") @Options(useGeneratedKeys=true,keyProperty="id") int insertProduct(Map<String,Object> row);
  @Update("UPDATE compute_product SET name=#{name},gpu_model=#{gpuModel},gpu_count=#{gpuCount},cpu_cores=#{cpuCores},memory_gb=#{memoryGb},price_cent=#{priceCent},enabled=#{enabled} WHERE id=#{id}") int updateProduct(Map<String,Object> row);

  @Select("SELECT c.id,c.code,c.name,c.region,c.status,COUNT(n.id) nodeCount,COALESCE(SUM(n.gpu_total),0) gpuTotal,COALESCE(SUM(n.gpu_allocated),0) gpuAllocated FROM compute_cluster c LEFT JOIN compute_node n ON n.cluster_id=c.id GROUP BY c.id ORDER BY c.id") List<Map<String,Object>> clusters();
  @Select("SELECT id,cluster_id clusterId,name,gpu_model gpuModel,gpu_total gpuTotal,gpu_allocated gpuAllocated,cpu_cores cpuCores,memory_gb memoryGb,status FROM compute_node WHERE cluster_id=#{clusterId} ORDER BY id") List<Map<String,Object>> nodes(long clusterId);
  @Select("SELECT id,code,name,region,status FROM compute_cluster WHERE id=#{id}") Map<String,Object> cluster(long id);
}
