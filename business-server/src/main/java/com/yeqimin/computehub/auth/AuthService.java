package com.yeqimin.computehub.auth;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.persistence.AuthMapper;
import com.yeqimin.computehub.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AuthMapper mapper; private final PasswordEncoder encoder; private final JwtService jwt;
  public AuthService(AuthMapper mapper, PasswordEncoder encoder, JwtService jwt) { this.mapper=mapper; this.encoder=encoder; this.jwt=jwt; }
  public Map<String,Object> login(String username, String password) {
    Map<String,Object> row = mapper.findByUsername(username);
    if (row == null || !Boolean.TRUE.equals(row.get("enabled")) || !encoder.matches(password, String.valueOf(row.get("passwordHash"))))
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    long id = ((Number) row.get("id")).longValue();
    Long tenantId = row.get("tenantId") == null ? null : ((Number)row.get("tenantId")).longValue();
    UserPrincipal principal = new UserPrincipal(id, tenantId, username, Set.copyOf(mapper.roles(id)), Set.copyOf(mapper.permissions(id)));
    return Map.of("token", jwt.issue(principal), "expiresIn", 28800, "user", profile(principal, String.valueOf(row.get("displayName"))));
  }
  public Map<String,Object> me() { UserPrincipal u = CurrentUser.get(); return profile(u, u.username()); }
  private Map<String,Object> profile(UserPrincipal u, String displayName) {
    Map<String,Object> value = new LinkedHashMap<>(); value.put("id",u.id()); value.put("tenantId",u.tenantId()); value.put("username",u.username());
    value.put("displayName",displayName); value.put("roles",u.roles()); value.put("permissions",u.permissions()); return value;
  }
}
