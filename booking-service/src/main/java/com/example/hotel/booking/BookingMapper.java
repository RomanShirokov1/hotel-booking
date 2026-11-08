package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BookingMapper {
  BookingView booking(Booking booking);

  UserView user(User user);
}
