package com.example.hotel.common.web;

import com.example.hotel.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiAdvice {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
    return ResponseEntity.status(e.status)
        .body(
            Errors.body(
                e.status, e.code, e.getMessage(), req.getRequestURI(), Map.of(), e.bookingId));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(
      MethodArgumentNotValidException e, HttpServletRequest req) {
    Map<String, String> fields = new TreeMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(Errors.body(400, "VALIDATION", "Invalid request", req.getRequestURI(), fields, null));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class
  })
  public ResponseEntity<ApiError> malformed(Exception e, HttpServletRequest req) {
    return api(new ApiException(400, "BAD_REQUEST", "Invalid or missing request parameters"), req);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest req) {
    return api(ApiException.conflict("Resource has dependencies or already exists"), req);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> denied(Exception e, HttpServletRequest req) {
    return api(new ApiException(403, "FORBIDDEN", "Access denied"), req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
    if (e instanceof org.springframework.web.ErrorResponse httpError)
      return api(
          new ApiException(
              httpError.getStatusCode().value(), "HTTP_ERROR", httpError.getBody().getTitle()),
          req);
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Unexpected error", e);
    return api(new ApiException(500, "INTERNAL_ERROR", "Unexpected server error"), req);
  }
}
