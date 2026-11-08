package com.example.hotel.booking;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "compensation_tasks")
@Getter
@Setter
@NoArgsConstructor
public class CompensationTask {
  @Id private UUID id;
  private Long bookingId;
  private String state;
  private int attempts;
  private Instant nextAttemptAt;
  private String lastError;
  @Version private long version;
}
