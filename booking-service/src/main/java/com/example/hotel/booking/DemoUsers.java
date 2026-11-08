package com.example.hotel.booking;

import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@Profile("demo")
public class DemoUsers {
  @Bean
  CommandLineRunner users(UserRepository users, org.springframework.jdbc.core.JdbcTemplate jdbc) {
    return args -> {
      if (users.count() > 0) return;
      var encoder = new BCryptPasswordEncoder();
      users.save(new User("admin", encoder.encode("Admin-demo-123"), User.Role.ADMIN));
      var alice = users.save(new User("alice", encoder.encode("Alice-demo-123"), User.Role.USER));
      var bob = users.save(new User("bob", encoder.encode("Bob-demo-123"), User.Role.USER));
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
        var key =
            java.util.UUID.nameUUIDFromBytes(
                ("demo-" + f[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update(
            "insert into bookings(id,user_id,room_id,hotel_id,auto_select,start_date,end_date,status,created_at,request_id,request_hash,trace_id,version) values(?,?,?,?,false,?,?,?,?,?,?,?,0)",
            f[0],
            f[0] == 900002 ? bob.getId() : alice.getId(),
            f[1],
            1L,
            base.plusDays(f[2]),
            base.plusDays(f[3]),
            f[0] == 900005 ? "CANCELLED" : "CONFIRMED",
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
            key,
            "demo",
            "demo-" + f[0]);
        if (f[0] != 900005)
          jdbc.update(
              "insert into booking_attempts(id,booking_id,room_id,start_date,end_date) values(?,?,?,?,?)",
              key,
              f[0],
              f[1],
              base.plusDays(f[2]),
              base.plusDays(f[3]));
      }
    };
  }
}
