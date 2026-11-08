package com.example.hotel.booking;

import jakarta.validation.constraints.*;
import java.time.*;

public final class BookingDtos {
  private BookingDtos() {}

  public record Credentials(
      @NotBlank @Size(min = 3, max = 80) String username,
      @NotBlank @Size(min = 8, max = 72) String password) {}

  public record UserInput(
      Long id,
      @NotBlank @Size(min = 3, max = 80) String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotNull User.Role role) {}

  public record UserView(Long id, String username, User.Role role) {}

  public record TokenView(String token, String tokenType, long expiresIn) {}

  public record CreateBooking(
      boolean autoSelect,
      Long roomId,
      Long hotelId,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record BookingView(
      Long id,
      Long userId,
      Long roomId,
      Long hotelId,
      boolean autoSelect,
      LocalDate startDate,
      LocalDate endDate,
      Booking.Status status,
      Instant createdAt) {}
}
