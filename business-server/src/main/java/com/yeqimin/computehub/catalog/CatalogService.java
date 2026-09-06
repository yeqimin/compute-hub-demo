package com.yeqimin.computehub.catalog;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.persistence.CatalogMapper;
import com.yeqimin.computehub.security.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final CatalogMapper mapper; private final PasswordEncoder encoder;
  public CatalogService(CatalogMapper mapper, PasswordEncoder encoder) { this.mapper=mapper; this.encoder=encoder; }
  public Map<String,Object> tenants(String keyword,int page,int size) { return page(mapper.tenants(blank(keyword),offset(page,size),safeSize(size)),mapper.tenantCount(blank(keyword)),page,size); }
  @Transactional public Map<String,Object> createTenant(String code,String name) {
    Map<String,Object> row=new HashMap<>(); row.put("code",code); row.put("name",name); mapper.insertTenant(row); mapper.insertWallet(((Number)row.get("id")).longValue()); return row;
  }
  public Map<String,Object> users(String keyword,int page,int size) { UserPrincipal u=CurrentUser.get(); Long tenant=u.platformAdmin()?null:u.tenantId();return page(mapper.users(tenant,blank(keyword),offset(page,size),safeSize(size)),mapper.userCount(tenant,blank(keyword)),page,size); }
  public List<Map<String,Object>> roles() { return mapper.roles(); }
  @Transactional public Map<String,Object> createUser(Long tenantId,String username,String displayName,String password,long roleId) {
    UserPrincipal caller=CurrentUser.get();if(!caller.platformAdmin()){tenantId=caller.tenantId();if(roleId==1)throw BusinessException.forbidden("租户管理员不能授予平台管理员角色");}
    Map<String,Object> row=new HashMap<>(); row.put("tenantId",tenantId); row.put("username",username); row.put("displayName",displayName); row.put("passwordHash",encoder.encode(password));
    mapper.insertUser(row); mapper.assignRole(((Number)row.get("id")).longValue(),roleId); row.remove("passwordHash"); return row;
  }
  @Transactional public void assignRoles(long userId,Set<String> roleCodes) {
    UserPrincipal caller=CurrentUser.get(); Map<String,Object> target=mapper.user(userId); if(target==null)throw BusinessException.notFound("用户不存在");
    Object targetTenantId=target.get("tenantId");
    if(!caller.platformAdmin() && (!(targetTenantId instanceof Number tenantId) || !Objects.equals(caller.tenantId(),tenantId.longValue()))) throw BusinessException.forbidden("不能修改其他租户用户");
    List<Map<String,Object>> roles=mapper.rolesByCodes(roleCodes); if(roles.size()!=roleCodes.size())throw BusinessException.badRequest("包含不存在的角色");
    if(!caller.platformAdmin() && roles.stream().anyMatch(role -> "PLATFORM_ADMIN".equals(role.get("code")))) throw BusinessException.forbidden("租户管理员不能授予平台管理员角色");
    if(!caller.platformAdmin() && userId==caller.id() && roleCodes.stream().noneMatch("TENANT_ADMIN"::equals))throw BusinessException.conflict("不能移除当前租户管理员角色");
    boolean targetIsPlatform=mapper.userRoleCodes(userId).contains("PLATFORM_ADMIN");
    if(targetIsPlatform && !roleCodes.contains("PLATFORM_ADMIN") && (userId==caller.id() || mapper.enabledPlatformAdminCount()<=1))throw BusinessException.conflict("不能移除最后一个平台管理员或当前管理员角色");
    mapper.clearUserRoles(userId); roles.forEach(r -> mapper.assignRole(userId,number(r,"id")));
  }
  @Transactional public void setUserEnabled(long userId,boolean enabled) {
    UserPrincipal caller=CurrentUser.get(); Map<String,Object> target=mapper.user(userId); if(target==null)throw BusinessException.notFound("用户不存在");
    Object targetTenantId=target.get("tenantId");
    if(!caller.platformAdmin() && (!(targetTenantId instanceof Number tenantId) || !Objects.equals(caller.tenantId(),tenantId.longValue())))throw BusinessException.forbidden("不能修改其他租户用户");
    if(userId==caller.id() && !enabled)throw BusinessException.conflict("不能停用当前登录管理员");
    if(!enabled && mapper.userRoleCodes(userId).contains("PLATFORM_ADMIN") && mapper.enabledPlatformAdminCount()<=1)throw BusinessException.conflict("不能停用最后一个平台管理员");
    mapper.setUserEnabled(userId,enabled);
  }
  public Map<String,Object> products(String keyword,Boolean enabled,int page,int size) { return page(mapper.products(blank(keyword),enabled,offset(page,size),safeSize(size)),mapper.productCount(blank(keyword),enabled),page,size); }
  public Map<String,Object> product(long id) { Map<String,Object> row=mapper.product(id); if(row==null) throw BusinessException.notFound("产品不存在"); return row; }
  @Transactional public Map<String,Object> saveProduct(Long id, Map<String,Object> row) { row.put("enabled", row.getOrDefault("enabled",true)); if(id==null){mapper.insertProduct(row);}else{row.put("id",id);if(mapper.updateProduct(row)==0)throw BusinessException.notFound("产品不存在");} return row; }
  public List<Map<String,Object>> clusters(){ return mapper.clusters(); }
  public List<Map<String,Object>> nodes(long clusterId){ if(mapper.cluster(clusterId)==null)throw BusinessException.notFound("集群不存在");return mapper.nodes(clusterId); }
  private static Map<String,Object> page(List<Map<String,Object>> items,long total,int requestedPage,int requestedSize){int size=safeSize(requestedSize),page=Math.max(1,requestedPage);return Map.of("items",items,"total",total,"page",page,"size",size);}
  private static int safeSize(int size){return Math.min(Math.max(size,1),100);}
  private static long offset(int page,int size){return ((long)Math.max(1,page)-1)*safeSize(size);}
  private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
  private static long number(Map<String,Object> row,String key){Object value=row.get(key);if(!(value instanceof Number number))throw BusinessException.badRequest("用户缺少租户归属");return number.longValue();}
}
