package com.yeqimin.computehub.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
  private CurrentUser() {}
  public static UserPrincipal get() { return (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); }
  public static long scopedTenant(Long requested) {
    UserPrincipal user = get();
    if (user.platformAdmin()) {
      if (requested == null) throw new IllegalArgumentException("平台管理员操作时必须指定 tenantId");
      return requested;
    }
    return user.tenantId();
  }
}
