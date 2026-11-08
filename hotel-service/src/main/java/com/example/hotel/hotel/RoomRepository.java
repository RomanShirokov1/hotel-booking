package com.example.hotel.hotel;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id=:id")
  Optional<Room> lock(@Param("id") Long id);

  boolean existsByHotelId(Long hotelId);
}
