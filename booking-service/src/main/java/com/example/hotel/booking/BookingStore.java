package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookingStore {
  private final BookingRepository bookings;
  private final AttemptRepository attempts;
  private final CompensationRepository tasks;
  private final UserRepository users;
  private final BookingMapper mapper;
  private static final Logger log = LoggerFactory.getLogger(BookingStore.class);

  public BookingStore(
      BookingRepository b,
      AttemptRepository a,
      CompensationRepository t,
      UserRepository u,
      BookingMapper m) {
    bookings = b;
    attempts = a;
    tasks = t;
    users = u;
    mapper = m;
  }

  public Booking create(Long user, UUID key, String hash, CreateBooking input) {
    users.findById(user).orElseThrow(ApiException::missing);
    var b = new Booking();
    b.setUserId(user);
    b.setRequestId(key);
    b.setRequestHash(hash);
    b.setRoomId(input.autoSelect() ? null : input.roomId());
    b.setHotelId(input.hotelId());
    b.setAutoSelect(input.autoSelect());
    b.setStartDate(input.startDate());
    b.setEndDate(input.endDate());
    b.setStatus(Booking.Status.PENDING);
    b.setCreatedAt(Instant.now());
    b.setTraceId(
        Optional.ofNullable(MDC.get("traceId")).orElseGet(() -> UUID.randomUUID().toString()));
    b = bookings.saveAndFlush(b);
    log.atInfo()
        .addKeyValue("bookingId", b.getId())
        .addKeyValue("state", "PENDING")
        .log("Booking state changed");
    return b;
  }

  @Transactional(readOnly = true)
  public Optional<Booking> existing(Long user, UUID key) {
    return bookings.findByUserIdAndRequestId(user, key);
  }

  public BookingAttempt attempt(Long id, Long room) {
    Booking b = lock(id);
    if (b.getStatus() != Booking.Status.PENDING)
      throw ApiException.conflict("Booking no longer pending");
    var a = new BookingAttempt();
    a.setId(UUID.randomUUID());
    a.setBookingId(id);
    a.setRoomId(room);
    a.setStartDate(b.getStartDate());
    a.setEndDate(b.getEndDate());
    return attempts.saveAndFlush(a);
  }

  public Booking confirmed(Long id, Long room, Long hotelId) {
    Booking b = lock(id);
    if (b.getStatus() == Booking.Status.PENDING) {
      b.setRoomId(room);
      b.setHotelId(hotelId);
      b.setStatus(Booking.Status.CONFIRMED);
      log.atInfo()
          .addKeyValue("bookingId", id)
          .addKeyValue("state", "CONFIRMED")
          .log("Booking state changed");
    }
    return b;
  }

  public Booking failed(Long id, int status, String code) {
    Booking b = lock(id);
    if (b.getStatus() == Booking.Status.PENDING) {
      b.setErrorStatus(status);
      b.setErrorCode(code);
      cancelLocked(b);
    }
    return b;
  }

  public BookingView cancel(Long id, Long user) {
    Booking b = lock(id);
    if (!b.getUserId().equals(user)) throw ApiException.missing();
    cancelLocked(b);
    return mapper.booking(b);
  }

  private void cancelLocked(Booking b) {
    if (b.getStatus() == Booking.Status.CANCELLED) return;
    b.setStatus(Booking.Status.CANCELLED);
    for (var a : attempts.findByBookingId(b.getId())) {
      if (!tasks.existsById(a.getId())) {
        var t = new CompensationTask();
        t.setId(a.getId());
        t.setBookingId(b.getId());
        t.setState("READY");
        t.setNextAttemptAt(Instant.now());
        tasks.save(t);
      }
    }
    log.atInfo()
        .addKeyValue("bookingId", b.getId())
        .addKeyValue("state", "CANCELLED")
        .addKeyValue("compensationQueued", true)
        .log("Booking state changed");
  }

  @Transactional(readOnly = true)
  public BookingView get(Long id, Long user) {
    Booking b = bookings.findById(id).orElseThrow(ApiException::missing);
    if (!b.getUserId().equals(user)) throw ApiException.missing();
    return mapper.booking(b);
  }

  @Transactional(readOnly = true)
  public PageResult<BookingView> history(Long user, int page, int size) {
    PageResult.validate(page, size);
    var p =
        bookings.findByUserId(
            user,
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    return new PageResult<>(
        p.getContent().stream().map(mapper::booking).toList(),
        page,
        size,
        p.getTotalElements(),
        p.getTotalPages());
  }

  public void taskDone(UUID id) {
    var t = tasks.findById(id).orElseThrow();
    t.setState("DONE");
    t.setLastError(null);
  }

  public void taskFailed(UUID id, Exception error) {
    var t = tasks.findById(id).orElseThrow();
    t.setAttempts(t.getAttempts() + 1);
    t.setLastError(error.getClass().getSimpleName());
    t.setNextAttemptAt(
        Instant.now().plusSeconds(Math.min(30, 1L << Math.min(5, t.getAttempts() - 1))));
  }

  private Booking lock(Long id) {
    return bookings.lock(id).orElseThrow(ApiException::missing);
  }
}
