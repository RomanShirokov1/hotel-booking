package com.example.hotel.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String trace = req.getHeader("X-Correlation-Id");
    if (trace == null || !trace.matches("[a-zA-Z0-9-]{1,64}")) trace = UUID.randomUUID().toString();
    MDC.put("traceId", trace);
    String request = req.getHeader("X-Request-Id");
    if (request != null && request.matches("[a-zA-Z0-9-]{1,64}")) MDC.put("requestId", request);
    res.setHeader("X-Correlation-Id", trace);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.clear();
    }
  }
}
