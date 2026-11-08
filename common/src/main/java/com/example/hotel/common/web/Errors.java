package com.example.hotel.common.web;

import com.example.hotel.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;

public final class Errors {
  private Errors() {}

  public static ApiError body(
      int status,
      String code,
      String message,
      String path,
      Map<String, String> fields,
      Long bookingId) {
    return new ApiError(
        Instant.now(), status, code, message, path, MDC.get("traceId"), fields, bookingId);
  }

  public static void write(
      HttpServletRequest req, HttpServletResponse res, ObjectMapper mapper, int status, String code)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    mapper.writeValue(
        res.getOutputStream(),
        body(
            status,
            code,
            status == 401 ? "Authentication required" : "Access denied",
            req.getRequestURI(),
            Map.of(),
            null));
  }
}
