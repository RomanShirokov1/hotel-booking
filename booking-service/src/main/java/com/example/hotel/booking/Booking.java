package com.example.hotel.booking;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long userId;
  private Long roomId;
  private Long hotelId;
  private boolean autoSelect;
  private LocalDate startDate;
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  private Status status;

  private Instant createdAt;
  private UUID requestId;
  private String requestHash;
  private String traceId;
  private Integer errorStatus;
  private String errorCode;
  @Version private long version;

  public enum Status {
    PENDING,
    CONFIRMED,
    CANCELLED
  }
}
