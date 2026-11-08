package com.example.hotel.booking;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompensationRepository extends JpaRepository<CompensationTask, UUID> {
  List<CompensationTask> findTop20ByStateAndNextAttemptAtBeforeOrderByNextAttemptAt(
      String state, Instant before);

  long countByState(String state);
}
