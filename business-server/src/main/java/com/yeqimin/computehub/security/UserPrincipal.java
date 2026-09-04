package com.yeqimin.computehub.security;

import java.util.Set;

public record UserPrincipal(Long id, Long tenantId, String username, Set<String> roles, Set<String> permissions) {
  public boolean platformAdmin() { return roles.contains("PLATFORM_ADMIN"); }
}
