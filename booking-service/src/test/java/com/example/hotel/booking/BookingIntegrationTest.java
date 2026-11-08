package com.example.hotel.booking;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:booking-test;DB_CLOSE_DELAY=-1",
      "booking.http.read-ms=200",
      "booking.http.retry-delay-ms=10",
      "booking.recovery-interval-ms=3600000",
      "booking.recovery-initial-delay-ms=3600000"
    })
@AutoConfigureMockMvc
class BookingIntegrationTest {
  static final WireMockServer remote = new WireMockServer(0);

  static {
    remote.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("booking.hotel-url", remote::baseUrl);
  }

  @AfterAll
  static void stop() {
    remote.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired BookingSaga saga;
  @Autowired BookingStore store;
  @Autowired BookingRepository bookings;
  @Autowired AttemptRepository attempts;
  @Autowired CompensationRepository tasks;
  @Autowired UserRepository users;
  @Autowired TokenService tokens;
  @Autowired HotelClient hotel;
  @Autowired RecoveryWorker worker;
  Long userId;
  String token;
  final LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(20);

  @BeforeEach
  void reset() {
    remote.resetAll();
    ((CircuitBreaker) ReflectionTestUtils.getField(hotel, "breaker")).reset();
    tasks.deleteAll();
    attempts.deleteAll();
    bookings.deleteAll();
    users.deleteAll();
    User u = users.save(new User("tester", "unused", User.Role.USER));
    userId = u.getId();
    token = tokens.user(u);
    remote.stubFor(
        WireMock.get(urlPathMatching("/api/rooms/[0-9]+"))
            .willReturn(
                okJson(
                    "{\"id\":1,\"hotelId\":1,\"number\":\"101\",\"available\":true,\"timesBooked\":0}")));
    remote.stubFor(
        WireMock.post(urlPathMatching("/api/rooms/[0-9]+/confirm-availability")).willReturn(ok()));
    remote.stubFor(WireMock.post(urlPathMatching("/api/rooms/[0-9]+/release")).willReturn(ok()));
  }

  CreateBooking request() {
    return new CreateBooking(false, 1L, null, start, start.plusDays(2));
  }

  BookingSaga.Result create() {
    return saga.create(userId, UUID.randomUUID(), request(), token);
  }

  @Test
  void successCommitsConfirmedBookingAndOneAttempt() {
    var result = create();
    assertThat(result.status()).isEqualTo(201);
    assertThat(result.booking().status()).isEqualTo(Booking.Status.CONFIRMED);
    assertThat(attempts.count()).isEqualTo(1);
    remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void repeatedRequestReusesResultAndChangedBodyConflicts() {
    UUID key = UUID.randomUUID();
    var first = saga.create(userId, key, request(), token);
    var again = saga.create(userId, key, request(), token);
    assertThat(again.booking()).isEqualTo(first.booking());
    assertThat(bookings.count()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                saga.create(
                    userId,
                    key,
                    new CreateBooking(false, 2L, null, start, start.plusDays(2)),
                    token))
        .isInstanceOf(ApiException.class);
    remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void twentyConcurrentDuplicatesRunOnlyOneSaga() throws Exception {
    UUID key = UUID.randomUUID();
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability"))
            .willReturn(ok().withFixedDelay(80)));
    try (var pool = Executors.newFixedThreadPool(20)) {
      var gate = new CountDownLatch(1);
      var futures = new ArrayList<Future<BookingSaga.Result>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return saga.create(userId, key, request(), token);
                }));
      gate.countDown();
      Set<Long> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(15, TimeUnit.SECONDS).booking().id());
      assertThat(ids).hasSize(1);
      assertThat(bookings.count()).isEqualTo(1);
      assertThat(attempts.count()).isEqualTo(1);
      remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
    }
  }

  @Test
  void remoteFailureQueuesCompensationAndReplaysOriginalFailure() {
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability")).willReturn(serverError()));
    UUID key = UUID.randomUUID();
    assertThatThrownBy(() -> saga.create(userId, key, request(), token))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(503));
    assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(tasks.countByState("READY")).isEqualTo(1);
    remote.verify(3, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
    assertThatThrownBy(() -> saga.create(userId, key, request(), token))
        .isInstanceOf(ApiException.class);
    remote.verify(3, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void timeoutAfterUncertainConfirmationStillReleasesSameOperation() {
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability"))
            .willReturn(ok().withFixedDelay(600)));
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(504));
    var attempt = attempts.findAll().getFirst();
    ((CircuitBreaker) ReflectionTestUtils.getField(hotel, "breaker")).reset();
    worker.recover();
    assertThat(tasks.countByState("DONE")).isEqualTo(1);
    remote.verify(
        postRequestedFor(urlEqualTo("/api/rooms/1/release"))
            .withRequestBody(containing(attempt.getId().toString())));
  }

  @Test
  void failedCompensationRemainsQueuedAndCompletesAfterRecovery() {
    var b = create().booking();
    store.cancel(b.id(), userId);
    remote.stubFor(WireMock.post(urlEqualTo("/api/rooms/1/release")).willReturn(serverError()));
    worker.recover();
    var task = tasks.findAll().getFirst();
    assertThat(task.getState()).isEqualTo("READY");
    assertThat(task.getAttempts()).isEqualTo(1);
    task.setNextAttemptAt(Instant.now().minusSeconds(1));
    tasks.save(task);
    ((CircuitBreaker) ReflectionTestUtils.getField(hotel, "breaker")).reset();
    remote.stubFor(WireMock.post(urlEqualTo("/api/rooms/1/release")).willReturn(ok()));
    worker.recover();
    assertThat(tasks.countByState("DONE")).isEqualTo(1);
  }

  @Test
  void businessConflictIsNotRetried() {
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability"))
            .willReturn(aResponse().withStatus(409)));
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void recoveryCancelsStalePending() {
    UUID key = UUID.randomUUID();
    var b = store.create(userId, key, "fixture-hash", request());
    assertThat(remote.getAllServeEvents()).isEmpty();
    b.setCreatedAt(Instant.now().minusSeconds(60));
    bookings.save(b);
    worker.recover();
    assertThat(bookings.findById(b.getId()).orElseThrow().getStatus())
        .isEqualTo(Booking.Status.CANCELLED);
  }

  @Test
  void cancelledPendingCannotBeResurrectedByLateSuccess() {
    var b = store.create(userId, UUID.randomUUID(), "hash", request());
    store.attempt(b.getId(), 1L);
    store.cancel(b.getId(), userId);
    assertThat(store.confirmed(b.getId(), 1L, 1L).getStatus()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(tasks.countByState("READY")).isEqualTo(1);
  }

  @Test
  void autoSelectionIgnoresRoomIdAndTriesNextCandidate() {
    remote.stubFor(
        WireMock.get(urlPathEqualTo("/api/rooms/recommend"))
            .willReturn(
                okJson(
                    "{\"content\":[{\"id\":1,\"hotelId\":1,\"number\":\"101\",\"available\":true,\"timesBooked\":0},{\"id\":2,\"hotelId\":1,\"number\":\"102\",\"available\":true,\"timesBooked\":0}],\"page\":0,\"size\":100,\"totalElements\":2,\"totalPages\":1}")));
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability"))
            .willReturn(aResponse().withStatus(409)));
    var b =
        saga.create(
                userId,
                UUID.randomUUID(),
                new CreateBooking(true, 999L, 1L, start, start.plusDays(2)),
                token)
            .booking();
    assertThat(b.roomId()).isEqualTo(2L);
    assertThat(attempts.count()).isEqualTo(2);
  }

  @Test
  void ownershipPaginationAndValidation() throws Exception {
    var b = create().booking();
    var other = users.save(new User("other", "unused", User.Role.USER));
    String otherToken = tokens.user(other);
    mvc.perform(get("/booking/" + b.id()).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/booking/" + b.id()).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/bookings").header("Authorization", "Bearer " + otherToken))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/bookings").header("Authorization", "Bearer " + token).param("size", "101"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/booking")
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(new CreateBooking(false, 1L, null, start, start))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/booking")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json.writeValueAsString(request())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authRegistrationCannotEscalateAndPasswordsAreHashed() throws Exception {
    mvc.perform(get("/bookings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/user").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/user/register")
                .contentType("application/json")
                .content(
                    "{\"username\":\"newuser\",\"password\":\"Password-123\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/user/register")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"password\":\"Password-123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresIn").value(3600));
    assertThat(users.findByUsername("newuser").orElseThrow().getPassword()).startsWith("$2");
    mvc.perform(
            post("/user/auth")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"password\":\"Incorrect-123\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/user/auth")
                .contentType("application/json")
                .content("{\"username\":\"newuser\",\"password\":\"Password-123\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/bookings").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/bookings").header("Authorization", "Bearer " + tokens.service()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void circuitBreakerStopsRepeatedRequestsToFailingHotel() {
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability")).willReturn(serverError()));
    assertThatThrownBy(this::create).isInstanceOf(ApiException.class);
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("HOTEL_CIRCUIT_OPEN"));
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("HOTEL_CIRCUIT_OPEN"));
    remote.verify(3, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void administrativeUserCrudAndHistoryProtection() throws Exception {
    var admin = users.save(new User("administrator", "unused", User.Role.ADMIN));
    String auth = "Bearer " + tokens.user(admin);
    var created =
        mvc.perform(
                post("/user")
                    .header("Authorization", auth)
                    .contentType("application/json")
                    .content(
                        "{\"username\":\"managed\",\"password\":\"Password-123\",\"role\":\"USER\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    mvc.perform(
            patch("/user")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(
                    "{\"id\":"
                        + id
                        + ",\"username\":\"updated\",\"password\":\"Changed-123\",\"role\":\"USER\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/user/" + id).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("updated"))
        .andExpect(jsonPath("$.password").doesNotExist());
    mvc.perform(get("/user").header("Authorization", auth).param("size", "1"))
        .andExpect(jsonPath("$.content.length()").value(1));
    mvc.perform(delete("/user").header("Authorization", auth).param("id", Long.toString(id)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/user/" + id).header("Authorization", auth)).andExpect(status().isNotFound());
    create();
    mvc.perform(delete("/user").header("Authorization", auth).param("id", userId.toString()))
        .andExpect(status().isConflict());
  }

  @Test
  void recommendationsAreAvailableFromBookingWithStableSorting() throws Exception {
    remote.stubFor(
        WireMock.get(urlPathEqualTo("/api/rooms/recommend"))
            .willReturn(
                okJson(
                    "{\"content\":[{\"id\":1,\"hotelId\":1,\"number\":\"101\",\"available\":true,\"timesBooked\":2},{\"id\":3,\"hotelId\":1,\"number\":\"103\",\"available\":true,\"timesBooked\":0},{\"id\":2,\"hotelId\":1,\"number\":\"102\",\"available\":true,\"timesBooked\":0}],\"page\":0,\"size\":3,\"totalElements\":3,\"totalPages\":1}")));
    mvc.perform(
            get("/booking/recommend")
                .header("Authorization", "Bearer " + token)
                .param("hotelId", "1")
                .param("startDate", start.toString())
                .param("endDate", start.plusDays(2).toString())
                .param("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(2))
        .andExpect(jsonPath("$.content[1].id").value(3))
        .andExpect(jsonPath("$.content[2].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(3));
    mvc.perform(
            get("/booking/recommend")
                .header("Authorization", "Bearer " + token)
                .param("startDate", start.toString())
                .param("endDate", start.minusDays(1).toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/booking/recommend")).andExpect(status().isUnauthorized());
    assertThat(bookings.count()).isZero();
  }

  @Test
  void nonexistentRoomIs404WithoutUnnecessaryCompensation() {
    remote.stubFor(WireMock.get(urlPathEqualTo("/api/rooms/1")).willReturn(notFound()));
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(Booking.Status.CANCELLED);
    assertThat(attempts.count()).isZero();
    assertThat(tasks.count()).isZero();
    remote.verify(0, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
  }

  @Test
  void roomDeletedBetweenLookupAndConfirmationDoesNotLeaveCompensationStuck() {
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability")).willReturn(notFound()));
    remote.stubFor(WireMock.post(urlEqualTo("/api/rooms/1/release")).willReturn(notFound()));
    assertThatThrownBy(this::create)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    assertThat(tasks.countByState("READY")).isEqualTo(1);
    worker.recover();
    assertThat(tasks.countByState("DONE")).isEqualTo(1);
    remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/confirm-availability")));
    remote.verify(1, postRequestedFor(urlEqualTo("/api/rooms/1/release")));
  }

  @Test
  void manualSelectionValidatesHotelAndStoresActualHotelId() {
    assertThatThrownBy(
            () ->
                saga.create(
                    userId,
                    UUID.randomUUID(),
                    new CreateBooking(false, 1L, 2L, start, start.plusDays(2)),
                    token))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("HOTEL_ROOM_MISMATCH"));
    assertThat(attempts.count()).isZero();
    assertThat(create().booking().hotelId()).isEqualTo(1L);
  }

  @Test
  void remainingDeadlineLimitsAnAlreadyStartedRemoteCall() {
    // Warm up the connection, then make a response slower than the remaining
    // budget but faster than the normal per-request timeout (200 ms).
    hotel.room(1L, token, System.nanoTime() + Duration.ofSeconds(5).toNanos());
    remote.stubFor(
        WireMock.post(urlEqualTo("/api/rooms/1/confirm-availability"))
            .willReturn(ok().withFixedDelay(150)));
    var b = store.create(userId, UUID.randomUUID(), "deadline-fixture", request());
    var attempt = store.attempt(b.getId(), 1L);
    assertThatThrownBy(
            () -> hotel.confirm(attempt, System.nanoTime() + Duration.ofMillis(50).toNanos()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(504));
  }
}
