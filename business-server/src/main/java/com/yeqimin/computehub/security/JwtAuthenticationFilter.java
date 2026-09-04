package com.yeqimin.computehub.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtService jwtService;
  public JwtAuthenticationFilter(JwtService jwtService) { this.jwtService = jwtService; }
  @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String auth = req.getHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
      try {
        UserPrincipal user = jwtService.verify(auth.substring(7));
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        user.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
      } catch (Exception ignored) { SecurityContextHolder.clearContext(); }
    }
    chain.doFilter(req, res);
  }
}
