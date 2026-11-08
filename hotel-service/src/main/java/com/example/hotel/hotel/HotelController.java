package com.example.hotel.hotel;

import com.example.hotel.common.*;
import com.example.hotel.hotel.HotelDtos.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('USER')")
public class HotelController {
  private final HotelCatalog catalog;
  private final ReservationService reservations;

  public HotelController(HotelCatalog c, ReservationService r) {
    catalog = c;
    reservations = r;
  }

  @PostMapping("/api/hotels")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView createHotel(@Valid @RequestBody HotelInput i) {
    return catalog.addHotel(i);
  }

  @GetMapping("/api/hotels")
  public PageResult<HotelView> hotels(
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String address,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.hotels(name, address, sort, page, size);
  }

  @GetMapping("/api/hotels/{id}")
  public HotelView hotel(@PathVariable Long id) {
    return catalog.hotel(id);
  }

  @PatchMapping("/api/hotels/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView updateHotel(@PathVariable Long id, @Valid @RequestBody HotelInput i) {
    return catalog.updateHotel(id, i);
  }

  @DeleteMapping("/api/hotels/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteHotel(@PathVariable Long id) {
    catalog.deleteHotel(id);
  }

  @PostMapping("/api/rooms")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView createRoom(@Valid @RequestBody RoomInput i) {
    return catalog.addRoom(i);
  }

  @GetMapping("/api/rooms/{id}")
  public RoomView room(@PathVariable Long id) {
    return catalog.room(id);
  }

  @PatchMapping("/api/rooms/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView updateRoom(@PathVariable Long id, @Valid @RequestBody RoomInput i) {
    return catalog.updateRoom(id, i);
  }

  @DeleteMapping("/api/rooms/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteRoom(@PathVariable Long id) {
    catalog.deleteRoom(id);
  }

  @GetMapping({"/api/rooms", "/api/rooms/recommend"})
  public PageResult<RoomView> rooms(
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam(required = false) Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.free(
        hotelId, startDate, endDate, request.getRequestURI().endsWith("/recommend"), page, size);
  }

  @GetMapping("/api/hotels/{id}/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  public Statistics hotelStatistics(
      @PathVariable Long id, @RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
    return catalog.hotelStats(id, startDate, endDate);
  }

  @GetMapping("/api/rooms/{id}/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  public Statistics roomStatistics(
      @PathVariable Long id, @RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
    return catalog.roomStats(id, startDate, endDate);
  }

  @PostMapping("/api/rooms/{id}/confirm-availability")
  @PreAuthorize("hasAuthority('SCOPE_room:reserve')")
  public void confirm(@PathVariable Long id, @Valid @RequestBody ReservationRequest r) {
    if (!reservations.confirm(id, r))
      throw ApiException.conflict("Room unavailable or operation cancelled");
  }

  @PostMapping("/api/rooms/{id}/release")
  @PreAuthorize("hasAuthority('SCOPE_room:reserve')")
  public void release(@PathVariable Long id, @Valid @RequestBody ReservationRequest r) {
    reservations.release(id, r);
  }
}
