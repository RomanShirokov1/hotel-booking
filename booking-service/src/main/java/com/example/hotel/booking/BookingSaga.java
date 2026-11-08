package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BookingSaga {
  private final BookingStore store;
  private final HotelOperations hotel;
  private final BookingMapper mapper;
  private final AutoSelectionCoordinator selections;
  private final long budget;

  public BookingSaga(
      BookingStore s,
      HotelOperations h,
      BookingMapper m,
      AutoSelectionCoordinator selections,
      @Value("${booking.saga-deadline-ms:20000}") long budget) {
    store = s;
    hotel = h;
    mapper = m;
    this.selections = selections;
    this.budget = budget;
  }

  public record Result(int status, BookingView booking) {}

  public Result create(Long user, UUID key, CreateBooking request, String jwt) {
    String hash = hash(request);
    var old = store.existing(user, key);
    if (old.isPresent()) return replay(old.get(), hash);
    validate(request);
    Booking booking;
    try {
      booking = store.create(user, key, hash, request);
    } catch (DataIntegrityViolationException e) {
      var existing = store.existing(user, key);
      if (existing.isPresent()) return replay(existing.get(), hash);
      throw e;
    }
    long deadline = System.nanoTime() + Duration.ofMillis(budget).toNanos();
    var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
    org.slf4j.MDC.put("bookingId", booking.getId().toString());
    try (var permit =
        selections.acquire(request.autoSelect() ? request.hotelId() : null, deadline)) {
      RoomView selected = request.autoSelect() ? null : hotel.room(request.roomId(), jwt, deadline);
      if (selected != null
          && request.hotelId() != null
          && !selected.hotelId().equals(request.hotelId()))
        throw new ApiException(
            400, "HOTEL_ROOM_MISMATCH", "Room does not belong to the requested hotel");
      Set<Long> tried = new HashSet<>();
      int candidates = request.autoSelect() ? 3 : 1;
      for (int i = 0; i < candidates; i++) {
        if (request.autoSelect()) {
          var choices =
              hotel.recommend(request, jwt, deadline).stream()
                  .sorted(
                      Comparator.comparingLong(RoomView::timesBooked).thenComparing(RoomView::id))
                  .filter(r -> !tried.contains(r.id()))
                  .toList();
          if (choices.isEmpty()) throw ApiException.conflict("No available rooms");
          selected = choices.getFirst();
        }
        Long room = selected.id();
        tried.add(room);
        var attempt = store.attempt(booking.getId(), room);
        try {
          hotel.confirm(attempt, deadline);
        } catch (ApiException conflict) {
          if (conflict.status == 409 && request.autoSelect() && i < candidates - 1) continue;
          throw conflict;
        }
        Booking confirmed = store.confirmed(booking.getId(), room, selected.hotelId());
        if (confirmed.getStatus() != Booking.Status.CONFIRMED)
          throw ApiException.conflict("Booking was cancelled during confirmation");
        return new Result(201, mapper.booking(confirmed));
      }
      throw ApiException.conflict("No available room after three candidates");
    } catch (RuntimeException failure) {
      ApiException error =
          failure instanceof ApiException a
              ? a
              : new ApiException(503, "BOOKING_FAILED", "Booking could not be confirmed");
      store.failed(booking.getId(), error.status, error.code);
      throw new ApiException(error.status, error.code, error.getMessage(), booking.getId());
    }
  }

  public PageResult<RoomView> recommendations(
      Long hotelId, LocalDate start, LocalDate end, int page, int size, String token) {
    PageResult.validate(page, size);
    if (start == null || end == null || !start.isBefore(end) || (hotelId != null && hotelId <= 0))
      throw new ApiException(400, "INVALID_QUERY", "Valid hotel and date range required");
    var result =
        hotel.recommendations(
            hotelId,
            start,
            end,
            page,
            size,
            token,
            System.nanoTime() + Duration.ofMillis(budget).toNanos());
    var sorted =
        result.content().stream()
            .sorted(Comparator.comparingLong(RoomView::timesBooked).thenComparing(RoomView::id))
            .toList();
    return new PageResult<>(
        sorted, result.page(), result.size(), result.totalElements(), result.totalPages());
  }

  private Result replay(Booking b, String hash) {
    if (!b.getRequestHash().equals(hash))
      throw ApiException.conflict("X-Request-Id reused with different request body");
    if (b.getErrorStatus() != null)
      throw new ApiException(
          b.getErrorStatus(), b.getErrorCode(), "Original booking request failed", b.getId());
    return new Result(b.getStatus() == Booking.Status.PENDING ? 202 : 200, mapper.booking(b));
  }

  private void validate(CreateBooking q) {
    if (q.startDate() == null
        || q.endDate() == null
        || !q.startDate().isBefore(q.endDate())
        || q.startDate().isBefore(LocalDate.now(ZoneOffset.UTC)))
      throw new ApiException(400, "INVALID_DATES", "Valid future dates required");
    if (q.autoSelect()
        ? (q.hotelId() == null || q.hotelId() <= 0)
        : (q.roomId() == null || q.roomId() <= 0))
      throw new ApiException(
          400, "INVALID_ROOM", "hotelId required for autoSelect, roomId otherwise");
  }

  private String hash(CreateBooking q) {
    String text =
        q.autoSelect()
            + "|"
            + (q.autoSelect() ? null : q.roomId())
            + "|"
            + q.hotelId()
            + "|"
            + q.startDate()
            + "|"
            + q.endDate();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
