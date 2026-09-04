package com.yeqimin.computehub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt, ObjectMapper mapper) throws Exception {
    return http.csrf(csrf -> csrf.disable()).cors(cors -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/v1/auth/login", "/api/v1/internal/engine/events", "/actuator/health/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(e -> e
            .authenticationEntryPoint((req,res,ex) -> write(res, 401, mapper, "UNAUTHORIZED", "请先登录"))
            .accessDeniedHandler((req,res,ex) -> write(res, 403, mapper, "FORBIDDEN", "没有操作权限")))
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build();
  }

  private static void write(HttpServletResponse res, int status, ObjectMapper mapper, String code, String message) throws java.io.IOException {
    res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE); mapper.writeValue(res.getWriter(), ApiResponse.error(code, message));
  }
}
