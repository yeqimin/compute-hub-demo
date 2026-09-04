package com.yeqimin.computehub.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final Algorithm algorithm;
  public JwtService(@Value("${compute-hub.jwt-secret}") String secret) { this.algorithm = Algorithm.HMAC256(secret); }
  public String issue(UserPrincipal user) {
    return JWT.create().withIssuer("compute-hub").withSubject(user.id().toString()).withClaim("username", user.username())
        .withClaim("tenantId", user.tenantId()).withArrayClaim("roles", user.roles().toArray(String[]::new))
        .withArrayClaim("permissions", user.permissions().toArray(String[]::new))
        .withIssuedAt(Date.from(Instant.now())).withExpiresAt(Date.from(Instant.now().plusSeconds(8 * 3600))).sign(algorithm);
  }
  public UserPrincipal verify(String token) {
    DecodedJWT jwt = JWT.require(algorithm).withIssuer("compute-hub").build().verify(token);
    Long tenantId = jwt.getClaim("tenantId").isNull() ? null : jwt.getClaim("tenantId").asLong();
    return new UserPrincipal(Long.valueOf(jwt.getSubject()), tenantId, jwt.getClaim("username").asString(),
        set(jwt.getClaim("roles").asArray(String.class)), set(jwt.getClaim("permissions").asArray(String.class)));
  }
  private static Set<String> set(String[] values) { return values == null ? Set.of() : Set.of(values); }
}
