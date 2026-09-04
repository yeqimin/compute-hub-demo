package com.yeqimin.computehub.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceFilter extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String traceId = request.getHeader("X-Trace-Id");
    if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString().replace("-", "");
    MDC.put("traceId", traceId); response.setHeader("X-Trace-Id", traceId);
    try { chain.doFilter(request, response); } finally { MDC.remove("traceId"); }
  }
}
