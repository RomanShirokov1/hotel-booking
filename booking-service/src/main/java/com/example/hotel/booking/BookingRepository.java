package com.example.hotel.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  Optional<Booking> findByUserIdAndRequestId(Long userId, UUID requestId);

  Page<Booking> findByUserId(Long userId, Pageable pageable);

  boolean existsByUserId(Long id);

  List<Booking> findByStatusAndCreatedAtBefore(Booking.Status status, Instant before);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id=:id")
  Optional<Booking> lock(@Param("id") Long id);
}
