package com.example.hotel.common;

public record RoomView(Long id, Long hotelId, String number, boolean available, long timesBooked) {}
