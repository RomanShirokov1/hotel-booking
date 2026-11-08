package com.example.hotel.hotel;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "room_reservations")
@Getter
@Setter
@NoArgsConstructor
public class RoomReservation {
  @Id private UUID id;
  private Long bookingId;
  private Long roomId;
  private LocalDate startDate;
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  private State state;

  public enum State {
    ACTIVE,
    RELEASED,
    REJECTED
  }

  public RoomReservation(
      UUID id, Long bookingId, Long roomId, LocalDate start, LocalDate end, State state) {
    this.id = id;
    this.bookingId = bookingId;
    this.roomId = roomId;
    this.startDate = start;
    this.endDate = end;
    this.state = state;
  }
}
