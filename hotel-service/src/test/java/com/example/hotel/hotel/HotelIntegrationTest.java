package com.example.hotel.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.common.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:hotel-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000"
    })
@AutoConfigureMockMvc
class HotelIntegrationTest {
  @Autowired ReservationService service;
  @Autowired HotelCatalog catalog;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired ReservationRepository reservations;
  @Autowired MockMvc mvc;
  Long hotelId;
  Long roomId;
  final LocalDate start = LocalDate.now().plusDays(10);

  @BeforeEach
  void fixtures() {
    reservations.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    hotelId = hotels.save(new Hotel("Test hotel", "Test address")).getId();
    roomId = rooms.save(new Room(hotelId, "101", true)).getId();
  }

  ReservationRequest request(long booking, LocalDate a, LocalDate b) {
    return new ReservationRequest(UUID.randomUUID(), booking, a, b);
  }

  @Test
  void twentyConcurrentRequestsHaveOneWinner() throws Exception {
    try (var executor = Executors.newFixedThreadPool(20)) {
      var ready = new CountDownLatch(20);
      var go = new CountDownLatch(1);
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 20; i++) {
        final int id = i + 1;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                  return service.confirm(roomId, request(id, start, start.plusDays(2)));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int winners = 0;
      for (var f : futures) if (f.get(15, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
      assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isEqualTo(1);
    }
  }

  @Test
  void adjacentDatesAreAllowedAndOverlapRejected() {
    assertThat(service.confirm(roomId, request(1, start, start.plusDays(2)))).isTrue();
    assertThat(service.confirm(roomId, request(2, start.plusDays(2), start.plusDays(4)))).isTrue();
    assertThat(service.confirm(roomId, request(3, start.plusDays(1), start.plusDays(3)))).isFalse();
  }

  @Test
  void duplicateConfirmationAndReleaseDoNotChangeCountTwice() {
    var q = request(1, start, start.plusDays(2));
    assertThat(service.confirm(roomId, q)).isTrue();
    assertThat(service.confirm(roomId, q)).isTrue();
    assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isEqualTo(1);
    service.release(roomId, q);
    service.release(roomId, q);
    assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isZero();
    assertThat(service.confirm(roomId, q)).isFalse();
  }

  @Test
  void releaseBeforeConfirmLeavesCancellationTombstone() {
    var q = request(1, start, start.plusDays(2));
    service.release(roomId, q);
    assertThat(service.confirm(roomId, q)).isFalse();
    assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isZero();
  }

  @Test
  void reusingOperationWithDifferentDatesConflicts() {
    var q = request(1, start, start.plusDays(2));
    service.confirm(roomId, q);
    var changed = new ReservationRequest(q.operationId(), 1L, start, start.plusDays(3));
    assertThatThrownBy(() -> service.confirm(roomId, changed))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different parameters");
  }

  @Test
  void recommendationsSortByCounterThenIdAndIgnoreMaintenance() {
    Long second = rooms.save(new Room(hotelId, "102", true)).getId();
    rooms.save(new Room(hotelId, "103", false));
    service.confirm(roomId, request(1, start.minusDays(4), start.minusDays(2)));
    var result = catalog.free(hotelId, start, start.plusDays(2), true, 0, 20);
    assertThat(result.content()).extracting(RoomView::id).containsExactly(second, roomId);
  }

  @Test
  void sequentialAutoSelectionBalancesCounters() {
    rooms.save(new Room(hotelId, "102", true));
    rooms.save(new Room(hotelId, "103", true));
    for (int i = 0; i < 12; i++) {
      var a = start.plusDays(i * 2L);
      var choice = catalog.free(hotelId, a, a.plusDays(1), true, 0, 20).content().getFirst();
      assertThat(service.confirm(choice.id(), request(i + 1, a, a.plusDays(1)))).isTrue();
    }
    assertThat(rooms.findAll()).extracting(Room::getTimesBooked).containsOnly(4L);
  }

  @Test
  void statisticsClipNightsToRequestedPeriod() {
    var q = request(1, start, start.plusDays(4));
    service.confirm(roomId, q);
    var result = catalog.roomStats(roomId, start.plusDays(1), start.plusDays(3));
    assertThat(result.occupiedNights()).isEqualTo(2);
    assertThat(result.occupancyPercent()).isEqualTo(100.0);
    service.release(roomId, q);
    assertThat(catalog.roomStats(roomId, start, start.plusDays(4)).occupiedNights()).isZero();
  }

  @Test
  void authenticationAndMethodRolesAreEnforced() throws Exception {
    mvc.perform(get("/api/hotels"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
    mvc.perform(
            post("/api/hotels")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType("application/json")
                .content("{\"name\":\"New\",\"address\":\"Address\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/hotels")
                .with(
                    jwt()
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_USER")))
                .contentType("application/json")
                .content("{\"name\":\"New\",\"address\":\"Address\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            get("/api/rooms")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .param("startDate", start.toString())
                .param("endDate", start.minusDays(1).toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void internalEndpointsRejectUserAuthorities() throws Exception {
    mvc.perform(
            post("/api/rooms/" + roomId + "/release")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void crudProtectsHistoryAndValidatesPayloads() throws Exception {
    var admin =
        jwt()
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    mvc.perform(
            patch("/api/hotels/" + hotelId)
                .with(admin)
                .contentType("application/json")
                .content("{\"name\":\"Updated\",\"address\":\"New address\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Updated"));
    mvc.perform(
            post("/api/rooms")
                .with(admin)
                .contentType("application/json")
                .content("{\"hotelId\":" + hotelId + ",\"number\":\"\",\"available\":true}"))
        .andExpect(status().isBadRequest());
    service.confirm(roomId, request(1, start, start.plusDays(1)));
    mvc.perform(delete("/api/rooms/" + roomId).with(admin)).andExpect(status().isConflict());
    mvc.perform(delete("/api/hotels/" + hotelId).with(admin)).andExpect(status().isConflict());
  }

  @Test
  void roomCrudPaginationAndHotelFilters() throws Exception {
    var admin =
        jwt()
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    var created =
        mvc.perform(
                post("/api/rooms")
                    .with(admin)
                    .contentType("application/json")
                    .content("{\"hotelId\":" + hotelId + ",\"number\":\"102\",\"available\":true}"))
            .andExpect(status().isCreated())
            .andReturn();
    long id =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.getResponse().getContentAsString())
            .path("id")
            .asLong();
    mvc.perform(get("/api/rooms/" + id).with(admin)).andExpect(status().isOk());
    mvc.perform(
            patch("/api/rooms/" + id)
                .with(admin)
                .contentType("application/json")
                .content("{\"hotelId\":" + hotelId + ",\"number\":\"102\",\"available\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
    mvc.perform(delete("/api/rooms/" + id).with(admin)).andExpect(status().isNoContent());
    mvc.perform(
            get("/api/hotels")
                .with(admin)
                .param("name", "Test")
                .param("sort", "name")
                .param("size", "1"))
        .andExpect(jsonPath("$.content.length()").value(1));
    mvc.perform(get("/api/hotels").with(admin).param("name", "Missing"))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void simultaneousConfirmationAndReleaseAlwaysEndReleased() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 10; i++) {
        var q = request(i + 1, start, start.plusDays(1));
        var gate = new CountDownLatch(1);
        var confirm =
            executor.submit(
                () -> {
                  gate.await();
                  return service.confirm(roomId, q);
                });
        var release =
            executor.submit(
                () -> {
                  gate.await();
                  service.release(roomId, q);
                  return true;
                });
        gate.countDown();
        confirm.get(10, TimeUnit.SECONDS);
        release.get(10, TimeUnit.SECONDS);
        assertThat(reservations.findById(q.operationId()).orElseThrow().getState())
            .isEqualTo(RoomReservation.State.RELEASED);
        assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isZero();
      }
    }
  }
}
