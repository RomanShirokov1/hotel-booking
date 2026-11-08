package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('USER')")
public class BookingController {
  private final BookingSaga saga;
  private final BookingStore store;

  public BookingController(BookingSaga s, BookingStore b) {
    saga = s;
    store = b;
  }

  @PostMapping("/booking")
  public ResponseEntity<BookingView> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Request-Id") UUID key,
      @Valid @RequestBody CreateBooking request) {
    var result = saga.create(Long.valueOf(jwt.getSubject()), key, request, jwt.getTokenValue());
    return ResponseEntity.status(result.status()).body(result.booking());
  }

  @GetMapping("/bookings")
  public PageResult<BookingView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return store.history(Long.valueOf(jwt.getSubject()), page, size);
  }

  @GetMapping("/booking/recommend")
  public PageResult<RoomView> recommend(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return saga.recommendations(hotelId, startDate, endDate, page, size, jwt.getTokenValue());
  }

  @GetMapping("/booking/{id}")
  public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
    return store.get(id, Long.valueOf(jwt.getSubject()));
  }

  @DeleteMapping("/booking/{id}")
  public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
    return store.cancel(id, Long.valueOf(jwt.getSubject()));
  }
}
