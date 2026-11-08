package com.example.hotel.booking;

import com.example.hotel.common.ReservationRequest;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "booking_attempts")
@Getter
@Setter
@NoArgsConstructor
public class BookingAttempt {
  @Id private UUID id;
  private Long bookingId;
  private Long roomId;
  private LocalDate startDate;
  private LocalDate endDate;

  public ReservationRequest request() {
    return new ReservationRequest(id, bookingId, startDate, endDate);
  }
}
