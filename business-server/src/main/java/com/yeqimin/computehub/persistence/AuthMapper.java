package com.yeqimin.computehub.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface AuthMapper {
  @Select("SELECT id, tenant_id tenantId, username, display_name displayName, password_hash passwordHash, enabled FROM sys_user WHERE username=#{username}")
  Map<String,Object> findByUsername(String username);

  @Select("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId}")
  List<String> roles(long userId);

  @Select("SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id=p.id JOIN sys_user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=#{userId}")
  List<String> permissions(long userId);

  @Update("UPDATE sys_user SET password_hash=#{hash} WHERE username=#{username} AND password_hash='BOOTSTRAP'")
  int initializePassword(@Param("username") String username, @Param("hash") String hash);
}
