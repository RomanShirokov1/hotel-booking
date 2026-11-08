package com.example.hotel.hotel;

import org.springframework.boot.*;
import org.springframework.context.annotation.*;

@Configuration
@Profile("demo")
public class DemoHotels {
  @Bean
  CommandLineRunner hotels(
      HotelRepository hotels, RoomRepository rooms, ReservationService reservations) {
    return args -> {
      if (hotels.count() > 0) return;
      var first = hotels.save(new Hotel("Aurora", "Moscow, Garden street 1"));
      var second = hotels.save(new Hotel("Seaside", "Sochi, Sea street 2"));
      for (int i = 1; i <= 3; i++) {
        rooms.save(new Room(first.getId(), "10" + i, true));
        rooms.save(new Room(second.getId(), "20" + i, true));
      }
      rooms.save(new Room(first.getId(), "199", false));
      var base =
          java.time.LocalDate.parse(
              System.getenv()
                  .getOrDefault(
                      "DEMO_BASE_DATE",
                      java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()));
      long[][] fixtures = {
        {900001, 1, -10, -8},
        {900002, 3, -6, -4},
        {900003, 5, 10, 12},
        {900004, 1, -15, -12},
        {900005, 1, -3, -2}
      };
      for (var f : fixtures) {
        var q =
            new com.example.hotel.common.ReservationRequest(
                java.util.UUID.nameUUIDFromBytes(
                    ("demo-" + f[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                f[0],
                base.plusDays(f[2]),
                base.plusDays(f[3]));
        reservations.confirm(f[1], q);
        if (f[0] == 900005) reservations.release(f[1], q);
      }
    };
  }
}
