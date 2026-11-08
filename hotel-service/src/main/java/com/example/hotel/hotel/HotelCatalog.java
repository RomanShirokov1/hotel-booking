package com.example.hotel.hotel;

import com.example.hotel.common.*;
import com.example.hotel.hotel.HotelDtos.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class HotelCatalog {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;
  private final HotelMapper mapper;

  public HotelCatalog(HotelRepository h, RoomRepository r, ReservationRepository s, HotelMapper m) {
    hotels = h;
    rooms = r;
    reservations = s;
    mapper = m;
  }

  public HotelView addHotel(HotelInput input) {
    return mapper.hotel(hotels.save(new Hotel(input.name(), input.address())));
  }

  public HotelView hotel(Long id) {
    return mapper.hotel(requireHotel(id));
  }

  public PageResult<HotelView> hotels(
      String name, String address, String sort, int page, int size) {
    var list =
        hotels.findAll().stream()
            .filter(
                h ->
                    name == null
                        || h.getName()
                            .toLowerCase(Locale.ROOT)
                            .contains(name.toLowerCase(Locale.ROOT)))
            .filter(
                h ->
                    address == null
                        || h.getAddress()
                            .toLowerCase(Locale.ROOT)
                            .contains(address.toLowerCase(Locale.ROOT)))
            .sorted(hotelOrder(sort))
            .map(mapper::hotel)
            .toList();
    return PageResult.of(list, page, size);
  }

  private Comparator<Hotel> hotelOrder(String sort) {
    return switch (sort) {
      case "id" -> Comparator.comparing(Hotel::getId);
      case "name" -> Comparator.comparing(Hotel::getName).thenComparing(Hotel::getId);
      default -> throw new ApiException(400, "INVALID_SORT", "sort must be id or name");
    };
  }

  public HotelView updateHotel(Long id, HotelInput i) {
    Hotel h = requireHotel(id);
    h.setName(i.name());
    h.setAddress(i.address());
    return mapper.hotel(h);
  }

  public void deleteHotel(Long id) {
    Hotel h = requireHotel(id);
    if (rooms.existsByHotelId(id)) throw ApiException.conflict("Hotel still has rooms");
    hotels.delete(h);
  }

  public RoomView addRoom(RoomInput i) {
    requireHotel(i.hotelId());
    return mapper.room(rooms.save(new Room(i.hotelId(), i.number(), i.available())));
  }

  public RoomView room(Long id) {
    return mapper.room(requireRoom(id));
  }

  public RoomView updateRoom(Long id, RoomInput i) {
    Room r = lockRoom(id);
    if (!r.getHotelId().equals(i.hotelId()))
      throw ApiException.conflict("Cannot move room between hotels");
    r.setNumber(i.number());
    r.setAvailable(i.available());
    return mapper.room(r);
  }

  public void deleteRoom(Long id) {
    Room r = lockRoom(id);
    if (reservations.existsByRoomId(id))
      throw ApiException.conflict("Room has reservation history");
    rooms.delete(r);
  }

  @Transactional(readOnly = true)
  public PageResult<RoomView> free(
      Long hotelId, LocalDate start, LocalDate end, boolean recommend, int page, int size) {
    dates(start, end);
    if (hotelId != null) requireHotel(hotelId);
    Comparator<Room> order =
        recommend
            ? Comparator.comparingLong(Room::getTimesBooked).thenComparing(Room::getId)
            : Comparator.comparing(Room::getId);
    var result =
        rooms.findAll().stream()
            .filter(Room::isAvailable)
            .filter(r -> hotelId == null || hotelId.equals(r.getHotelId()))
            .filter(r -> !reservations.overlaps(r.getId(), start, end))
            .sorted(order)
            .map(mapper::room)
            .toList();
    return PageResult.of(result, page, size);
  }

  public Statistics roomStats(Long id, LocalDate start, LocalDate end) {
    requireRoom(id);
    return statistics(List.of(id), start, end);
  }

  public Statistics hotelStats(Long id, LocalDate start, LocalDate end) {
    requireHotel(id);
    return statistics(
        rooms.findAll().stream().filter(r -> id.equals(r.getHotelId())).map(Room::getId).toList(),
        start,
        end);
  }

  private Statistics statistics(List<Long> ids, LocalDate start, LocalDate end) {
    dates(start, end);
    long nights = 0, count = 0;
    for (Long id : ids)
      for (var r : reservations.findByRoomIdAndState(id, RoomReservation.State.ACTIVE)) {
        LocalDate a = start.isAfter(r.getStartDate()) ? start : r.getStartDate(),
            b = end.isBefore(r.getEndDate()) ? end : r.getEndDate();
        if (a.isBefore(b)) {
          nights += ChronoUnit.DAYS.between(a, b);
          count++;
        }
      }
    long capacity = ids.size() * ChronoUnit.DAYS.between(start, end);
    return new Statistics(count, nights, capacity, capacity == 0 ? 0 : 100.0 * nights / capacity);
  }

  public static void dates(LocalDate start, LocalDate end) {
    if (start == null || end == null || !start.isBefore(end))
      throw new ApiException(400, "INVALID_DATES", "startDate must precede endDate");
  }

  private Hotel requireHotel(Long id) {
    return hotels.findById(id).orElseThrow(ApiException::missing);
  }

  private Room requireRoom(Long id) {
    return rooms.findById(id).orElseThrow(ApiException::missing);
  }

  private Room lockRoom(Long id) {
    return rooms.lock(id).orElseThrow(ApiException::missing);
  }
}
