package com.example.hotel.hotel;

import jakarta.validation.constraints.*;

public final class HotelDtos {
  private HotelDtos() {}

  public record HotelInput(
      @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 300) String address) {}

  public record RoomInput(
      @NotNull Long hotelId, @NotBlank @Size(max = 30) String number, @NotNull Boolean available) {}

  public record HotelView(Long id, String name, String address) {}

  public record Statistics(
      long reservations, long occupiedNights, long capacityNights, double occupancyPercent) {}
}
