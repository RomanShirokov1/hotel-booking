package com.example.hotel.hotel;

import com.example.hotel.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public ReservationService(RoomRepository r, ReservationRepository s) {
    rooms = r;
    reservations = s;
  }

  @Transactional
  public boolean confirm(Long roomId, ReservationRequest request) {
    HotelCatalog.dates(request.startDate(), request.endDate());
    Room room = rooms.lock(roomId).orElseThrow(ApiException::missing);
    var old = reservations.findById(request.operationId());
    if (old.isPresent()) {
      validate(old.get(), roomId, request);
      return old.get().getState() == RoomReservation.State.ACTIVE;
    }
    boolean free =
        room.isAvailable()
            && !reservations.overlaps(roomId, request.startDate(), request.endDate());
    reservations.save(
        new RoomReservation(
            request.operationId(),
            request.bookingId(),
            roomId,
            request.startDate(),
            request.endDate(),
            free ? RoomReservation.State.ACTIVE : RoomReservation.State.REJECTED));
    if (free) room.setTimesBooked(room.getTimesBooked() + 1);
    log.atInfo()
        .addKeyValue("bookingId", request.bookingId())
        .addKeyValue("operationId", request.operationId())
        .addKeyValue("roomId", roomId)
        .addKeyValue("accepted", free)
        .log("Reservation decision");
    return free;
  }

  @Transactional
  public void release(Long roomId, ReservationRequest request) {
    HotelCatalog.dates(request.startDate(), request.endDate());
    Room room = rooms.lock(roomId).orElseThrow(ApiException::missing);
    var old = reservations.findById(request.operationId());
    if (old.isEmpty()) {
      reservations.save(
          new RoomReservation(
              request.operationId(),
              request.bookingId(),
              roomId,
              request.startDate(),
              request.endDate(),
              RoomReservation.State.RELEASED));
    } else {
      var reservation = old.get();
      validate(reservation, roomId, request);
      if (reservation.getState() == RoomReservation.State.ACTIVE)
        room.setTimesBooked(room.getTimesBooked() - 1);
      reservation.setState(RoomReservation.State.RELEASED);
    }
    log.atInfo()
        .addKeyValue("bookingId", request.bookingId())
        .addKeyValue("operationId", request.operationId())
        .addKeyValue("roomId", roomId)
        .log("Reservation released");
  }

  private void validate(RoomReservation r, Long room, ReservationRequest q) {
    if (!r.getRoomId().equals(room)
        || !r.getBookingId().equals(q.bookingId())
        || !r.getStartDate().equals(q.startDate())
        || !r.getEndDate().equals(q.endDate()))
      throw ApiException.conflict("Operation key reused with different parameters");
  }
}
