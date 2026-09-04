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
  public List<Map<String,Object>> tenants() { return mapper.tenants(); }
  @Transactional public Map<String,Object> createTenant(String code,String name) {
    Map<String,Object> row=new HashMap<>(); row.put("code",code); row.put("name",name); mapper.insertTenant(row); mapper.insertWallet(((Number)row.get("id")).longValue()); return row;
  }
  public List<Map<String,Object>> users() { UserPrincipal u=CurrentUser.get();return mapper.users(u.platformAdmin()?null:u.tenantId()); }
  public List<Map<String,Object>> roles() { return mapper.roles(); }
  @Transactional public Map<String,Object> createUser(Long tenantId,String username,String displayName,String password,long roleId) {
    UserPrincipal caller=CurrentUser.get();if(!caller.platformAdmin()){tenantId=caller.tenantId();if(roleId==1)throw BusinessException.forbidden("租户管理员不能授予平台管理员角色");}
    Map<String,Object> row=new HashMap<>(); row.put("tenantId",tenantId); row.put("username",username); row.put("displayName",displayName); row.put("passwordHash",encoder.encode(password));
    mapper.insertUser(row); mapper.assignRole(((Number)row.get("id")).longValue(),roleId); row.remove("passwordHash"); return row;
  }
  @Transactional public void assignRoles(long userId,List<Long> roleIds) { mapper.clearUserRoles(userId); roleIds.forEach(r -> mapper.assignRole(userId,r)); }
  public List<Map<String,Object>> products() { return mapper.products(); }
  public Map<String,Object> product(long id) { Map<String,Object> row=mapper.product(id); if(row==null) throw BusinessException.notFound("产品不存在"); return row; }
  @Transactional public Map<String,Object> saveProduct(Long id, Map<String,Object> row) { row.put("enabled", row.getOrDefault("enabled",true)); if(id==null){mapper.insertProduct(row);}else{row.put("id",id);if(mapper.updateProduct(row)==0)throw BusinessException.notFound("产品不存在");} return row; }
  public List<Map<String,Object>> clusters(){ return mapper.clusters(); }
  public List<Map<String,Object>> nodes(long clusterId){ if(mapper.cluster(clusterId)==null)throw BusinessException.notFound("集群不存在");return mapper.nodes(clusterId); }
}
