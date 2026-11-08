package com.example.hotel.common;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;
  public final Long bookingId;

  public ApiException(int status, String code, String message) {
    this(status, code, message, null);
  }

  public ApiException(int status, String code, String message, Long bookingId) {
    super(message);
    this.status = status;
    this.code = code;
    this.bookingId = bookingId;
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, "CONFLICT", message);
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "Resource not found");
  }
}
