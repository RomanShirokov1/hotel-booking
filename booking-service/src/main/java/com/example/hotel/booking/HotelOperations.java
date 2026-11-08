package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.CreateBooking;
import com.example.hotel.common.PageResult;
import com.example.hotel.common.RoomView;
import java.time.LocalDate;
import java.util.List;

/** Port used by the saga; HTTP and resilience remain in the adapter. */
public interface HotelOperations {
  List<RoomView> recommend(CreateBooking request, String userToken, long deadline);

  PageResult<RoomView> recommendations(
      Long hotelId,
      LocalDate start,
      LocalDate end,
      int page,
      int size,
      String userToken,
      long deadline);

  RoomView room(Long roomId, String userToken, long deadline);

  void confirm(BookingAttempt attempt, long deadline);

  void release(BookingAttempt attempt);
}
