package com.example.hotel.hotel;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<RoomReservation, UUID> {
  @Query(
      "select count(r)>0 from RoomReservation r where r.roomId=:room and r.state=com.example.hotel.hotel.RoomReservation.State.ACTIVE and r.startDate<:end and r.endDate>:start")
  boolean overlaps(
      @Param("room") Long room, @Param("start") LocalDate start, @Param("end") LocalDate end);

  List<RoomReservation> findByRoomIdAndState(Long roomId, RoomReservation.State state);

  boolean existsByRoomId(Long roomId);
}
