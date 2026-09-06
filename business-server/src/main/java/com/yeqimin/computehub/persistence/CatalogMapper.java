package com.yeqimin.computehub.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface CatalogMapper {
  @Select("SELECT id,code,name,status,created_at createdAt FROM tenant WHERE (#{keyword} IS NULL OR code LIKE CONCAT('%',#{keyword},'%') OR name LIKE CONCAT('%',#{keyword},'%')) ORDER BY id LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> tenants(@Param("keyword") String keyword,@Param("offset") long offset,@Param("size") int size);
  @Select("SELECT COUNT(*) FROM tenant WHERE (#{keyword} IS NULL OR code LIKE CONCAT('%',#{keyword},'%') OR name LIKE CONCAT('%',#{keyword},'%'))") long tenantCount(@Param("keyword") String keyword);
  @Insert("INSERT INTO tenant(code,name,status) VALUES(#{code},#{name},'ACTIVE')") @Options(useGeneratedKeys=true,keyProperty="id") int insertTenant(Map<String,Object> row);
  @Insert("INSERT INTO tenant_wallet(tenant_id,available_cent,frozen_cent) VALUES(#{id},0,0)") int insertWallet(long id);

  @Select("<script>SELECT u.id,u.tenant_id tenantId,t.name tenantName,u.username,u.display_name displayName,u.enabled,GROUP_CONCAT(DISTINCT r.code ORDER BY r.id) roles FROM sys_user u LEFT JOIN tenant t ON t.id=u.tenant_id LEFT JOIN sys_user_role ur ON ur.user_id=u.id LEFT JOIN sys_role r ON r.id=ur.role_id WHERE (#{tenantId} IS NULL OR u.tenant_id=#{tenantId}) <if test='keyword != null'>AND (u.username LIKE CONCAT('%',#{keyword},'%') OR u.display_name LIKE CONCAT('%',#{keyword},'%'))</if> GROUP BY u.id ORDER BY u.id LIMIT #{size} OFFSET #{offset}</script>") List<Map<String,Object>> users(@Param("tenantId") Long tenantId,@Param("keyword") String keyword,@Param("offset") long offset,@Param("size") int size);
  @Select("<script>SELECT COUNT(*) FROM sys_user u WHERE (#{tenantId} IS NULL OR u.tenant_id=#{tenantId}) <if test='keyword != null'>AND (u.username LIKE CONCAT('%',#{keyword},'%') OR u.display_name LIKE CONCAT('%',#{keyword},'%'))</if></script>") long userCount(@Param("tenantId") Long tenantId,@Param("keyword") String keyword);
  @Select("SELECT r.id,r.code,r.name,GROUP_CONCAT(DISTINCT p.code ORDER BY p.id) permissions FROM sys_role r LEFT JOIN sys_role_permission rp ON rp.role_id=r.id LEFT JOIN sys_permission p ON p.id=rp.permission_id GROUP BY r.id ORDER BY r.id") List<Map<String,Object>> roles();
  @Select("SELECT u.id,u.tenant_id tenantId,u.username,u.display_name displayName,u.enabled FROM sys_user u WHERE u.id=#{id}") Map<String,Object> user(long id);
  @Select("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId}") List<String> userRoleCodes(long userId);
  @Select("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.enabled=1 AND r.code='PLATFORM_ADMIN'") long enabledPlatformAdminCount();
  @Select("<script>SELECT id,code,name FROM sys_role WHERE code IN <foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach> ORDER BY id</script>") List<Map<String,Object>> rolesByCodes(@Param("codes") Collection<String> codes);
  @Insert("INSERT INTO sys_user(tenant_id,username,display_name,password_hash,enabled) VALUES(#{tenantId},#{username},#{displayName},#{passwordHash},1)") @Options(useGeneratedKeys=true,keyProperty="id") int insertUser(Map<String,Object> row);
  @Delete("DELETE FROM sys_user_role WHERE user_id=#{userId}") int clearUserRoles(long userId);
  @Insert("INSERT INTO sys_user_role(user_id,role_id) VALUES(#{userId},#{roleId})") int assignRole(@Param("userId") long userId,@Param("roleId") long roleId);
  @Update("UPDATE sys_user SET enabled=#{enabled} WHERE id=#{id}") int setUserEnabled(@Param("id") long id,@Param("enabled") boolean enabled);

  @Select("<script>SELECT id,sku,name,gpu_model gpuModel,gpu_count gpuCount,cpu_cores cpuCores,memory_gb memoryGb,price_cent priceCent,enabled,created_at createdAt FROM compute_product WHERE 1=1 <if test='keyword != null'>AND (sku LIKE CONCAT('%',#{keyword},'%') OR name LIKE CONCAT('%',#{keyword},'%') OR gpu_model LIKE CONCAT('%',#{keyword},'%'))</if><if test='enabled != null'> AND enabled=#{enabled}</if> ORDER BY id LIMIT #{size} OFFSET #{offset}</script>") List<Map<String,Object>> products(@Param("keyword") String keyword,@Param("enabled") Boolean enabled,@Param("offset") long offset,@Param("size") int size);
  @Select("<script>SELECT COUNT(*) FROM compute_product WHERE 1=1 <if test='keyword != null'>AND (sku LIKE CONCAT('%',#{keyword},'%') OR name LIKE CONCAT('%',#{keyword},'%') OR gpu_model LIKE CONCAT('%',#{keyword},'%'))</if><if test='enabled != null'> AND enabled=#{enabled}</if></script>") long productCount(@Param("keyword") String keyword,@Param("enabled") Boolean enabled);
  @Select("SELECT id,sku,name,gpu_model gpuModel,gpu_count gpuCount,cpu_cores cpuCores,memory_gb memoryGb,price_cent priceCent,enabled FROM compute_product WHERE id=#{id}") Map<String,Object> product(long id);
  @Insert("INSERT INTO compute_product(sku,name,gpu_model,gpu_count,cpu_cores,memory_gb,price_cent,enabled) VALUES(#{sku},#{name},#{gpuModel},#{gpuCount},#{cpuCores},#{memoryGb},#{priceCent},#{enabled})") @Options(useGeneratedKeys=true,keyProperty="id") int insertProduct(Map<String,Object> row);
  @Update("UPDATE compute_product SET name=#{name},gpu_model=#{gpuModel},gpu_count=#{gpuCount},cpu_cores=#{cpuCores},memory_gb=#{memoryGb},price_cent=#{priceCent},enabled=#{enabled} WHERE id=#{id}") int updateProduct(Map<String,Object> row);

  @Select("SELECT c.id,c.code,c.name,c.region,c.status,COUNT(n.id) nodeCount,COALESCE(SUM(n.gpu_total),0) gpuTotal,COALESCE(SUM(n.gpu_allocated),0) gpuAllocated FROM compute_cluster c LEFT JOIN compute_node n ON n.cluster_id=c.id GROUP BY c.id ORDER BY c.id") List<Map<String,Object>> clusters();
  @Select("SELECT id,cluster_id clusterId,name,gpu_model gpuModel,gpu_total gpuTotal,gpu_allocated gpuAllocated,cpu_cores cpuCores,memory_gb memoryGb,status FROM compute_node WHERE cluster_id=#{clusterId} ORDER BY id") List<Map<String,Object>> nodes(long clusterId);
  @Select("SELECT id,code,name,region,status FROM compute_cluster WHERE id=#{id}") Map<String,Object> cluster(long id);
}
