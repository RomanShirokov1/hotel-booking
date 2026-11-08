package com.example.hotel.hotel;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long hotelId;
  private String number;
  private boolean available = true;
  private long timesBooked;
  @Version private long version;

  public Room(Long hotelId, String number, boolean available) {
    this.hotelId = hotelId;
    this.number = number;
    this.available = available;
  }
}
