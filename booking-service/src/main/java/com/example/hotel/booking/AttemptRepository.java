package com.example.hotel.booking;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<BookingAttempt, UUID> {
  List<BookingAttempt> findByBookingId(Long id);
}
