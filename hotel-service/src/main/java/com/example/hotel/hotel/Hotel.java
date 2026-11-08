package com.example.hotel.hotel;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "hotels")
@Getter
@Setter
@NoArgsConstructor
public class Hotel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;
  private String address;
  @Version private long version;

  public Hotel(String name, String address) {
    this.name = name;
    this.address = address;
  }
}
