package com.example.hotel.hotel;

import com.example.hotel.common.RoomView;
import com.example.hotel.hotel.HotelDtos.HotelView;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface HotelMapper {
  RoomView room(Room room);

  HotelView hotel(Hotel hotel);
}
