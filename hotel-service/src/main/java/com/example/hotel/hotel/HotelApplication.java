package com.example.hotel.hotel;

import com.example.hotel.common.web.UserSecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(UserSecurityConfig.class)
public class HotelApplication {
  public static void main(String[] args) {
    SpringApplication.run(HotelApplication.class, args);
  }
}
