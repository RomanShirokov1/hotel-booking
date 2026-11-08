package com.example.hotel.system;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

/** Real applications; a forwarding proxy can lose replies AFTER Hotel commits. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemIT {
  static final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  static final ObjectMapper json = new ObjectMapper();
  static final List<Process> processes = new ArrayList<>();
  static final AtomicBoolean loseReplies = new AtomicBoolean();
  static Path root;
  static int registry, hotel, booking, gateway;
  static HttpServer proxy;
  static String alice, bob, admin;
  static final LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(40);

  @BeforeAll
  static void launch() throws Exception {
    root = Path.of("..").toAbsolutePath().normalize();
    Set<Integer> ports = new HashSet<>();
    while (ports.size() < 4)
      try (var socket = new ServerSocket(0)) {
        ports.add(socket.getLocalPort());
      }
    var i = ports.iterator();
    registry = i.next();
    hotel = i.next();
    booking = i.next();
    gateway = i.next();
    try {
      start("discovery-server", registry);
      await(() -> health(registry), 60);
      start("hotel-service", hotel);
      proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      proxy.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      proxy.createContext("/", SystemIT::forward);
      proxy.start();
      start(
          "booking-service",
          booking,
          "--booking.hotel-url=http://localhost:" + proxy.getAddress().getPort());
      start("api-gateway", gateway);
      await(() -> health(hotel) && health(booking) && health(gateway), 90);
      await(
          () -> {
            try {
              alice = login("alice", "Alice-demo-123");
              return alice != null;
            } catch (Exception e) {
              return false;
            }
          },
          90);
      bob = login("bob", "Bob-demo-123");
      admin = login("admin", "Admin-demo-123");
    } catch (Exception | AssertionError e) {
      stop();
      throw e;
    }
  }

  static void start(String module, int port, String... extras) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-jar",
                root.resolve(module + "/target/" + module + "-1.0.0.jar").toString(),
                "--server.port=" + port,
                "--spring.profiles.active=demo",
                "--eureka.client.registry-fetch-interval-seconds=2",
                "--eureka.instance.initial-instance-info-replication-interval-seconds=1",
                "--spring.cloud.loadbalancer.cache.enabled=false"));
    command.addAll(List.of(extras));
    var builder =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("system-tests/target/" + module + ".log").toFile());
    builder
        .environment()
        .put(
            "EUREKA_URL",
            "http://registry:local-registry-password@localhost:" + registry + "/eureka/");
    builder.environment().put("EUREKA_PASSWORD", "local-registry-password");
    processes.add(builder.start());
  }

  static void forward(HttpExchange exchange) throws IOException {
    try {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + hotel + exchange.getRequestURI()))
              .method(
                  exchange.getRequestMethod(),
                  HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
      for (String name :
          List.of("Authorization", "Content-Type", "X-Request-Id", "X-Correlation-Id")) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) request.header(name, value);
      }
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (loseReplies.get() && exchange.getRequestURI().getPath().endsWith("confirm-availability"))
        Thread.sleep(2500);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(
          response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
      if (response.body().length > 0) exchange.getResponseBody().write(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  static boolean health(int port) {
    try {
      return send(port, "GET", "/actuator/health", null, null, null).statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  static void await(BooleanSupplier condition, int seconds) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) return;
      for (Process p : processes)
        assertThat(p.isAlive())
            .as("Application exited; inspect system-tests/target/*.log")
            .isTrue();
      Thread.sleep(250);
    }
    fail("Condition not reached within " + seconds + "s; inspect application logs");
  }

  static HttpResponse<String> send(
      int port, String method, String path, String token, String key, String body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (key != null) b.header("X-Request-Id", key);
    if (body != null) b.header("Content-Type", "application/json");
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String login(String name, String password) throws Exception {
    var response =
        send(
            gateway,
            "POST",
            "/user/auth",
            null,
            null,
            "{\"username\":\"" + name + "\",\"password\":\"" + password + "\"}");
    return response.statusCode() == 200
        ? json.readTree(response.body()).path("token").asText()
        : null;
  }

  static String body(long room, LocalDate date) {
    return "{\"roomId\":"
        + room
        + ",\"autoSelect\":false,\"startDate\":\""
        + date
        + "\",\"endDate\":\""
        + date.plusDays(2)
        + "\"}";
  }

  @Test
  @Order(1)
  void realGatewayDiscoveryJwtAndCrud() throws Exception {
    var registryReply = send(registry, "GET", "/eureka/apps", null, null, null);
    assertThat(registryReply.statusCode()).isEqualTo(401);
    var hotels = send(gateway, "GET", "/api/hotels", alice, null, null);
    assertThat(hotels.statusCode()).isEqualTo(200);
    assertThat(json.readTree(hotels.body()).path("totalElements").asInt()).isEqualTo(2);
    var b =
        send(gateway, "POST", "/api/bookings", alice, UUID.randomUUID().toString(), body(1, start));
    assertThat(b.statusCode()).withFailMessage(b.body()).isEqualTo(201);
    long id = json.readTree(b.body()).path("id").asLong();
    String trace = b.headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(Files.readString(root.resolve("system-tests/target/booking-service.log")))
        .contains("\"traceId\":\"" + trace + "\"")
        .contains("\"bookingId\":" + id);
    assertThat(Files.readString(root.resolve("system-tests/target/hotel-service.log")))
        .contains("\"traceId\":\"" + trace + "\"")
        .contains("\"bookingId\":" + id);
    assertThat(send(gateway, "GET", "/api/bookings/" + id, bob, null, null).statusCode())
        .isEqualTo(404);
    assertThat(send(gateway, "GET", "/api/bookings/" + id, alice, null, null).statusCode())
        .isEqualTo(200);
    assertThat(send(gateway, "GET", "/api/bookings", alice, null, null).statusCode())
        .isEqualTo(200);
    assertThat(send(gateway, "POST", "/api/rooms/1/release", alice, null, "{}").statusCode())
        .isEqualTo(403);
    assertThat(send(hotel, "POST", "/api/rooms/1/release", alice, null, "{}").statusCode())
        .isEqualTo(401);
    assertThat(
            send(
                    gateway,
                    "POST",
                    "/api/hotels",
                    alice,
                    null,
                    "{\"name\":\"Forbidden\",\"address\":\"Address\"}")
                .statusCode())
        .isEqualTo(403);
    var created =
        send(
            gateway,
            "POST",
            "/api/hotels",
            admin,
            null,
            "{\"name\":\"Created\",\"address\":\"Address\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    long hotelId = json.readTree(created.body()).path("id").asLong();
    assertThat(send(gateway, "DELETE", "/api/hotels/" + hotelId, admin, null, null).statusCode())
        .isEqualTo(204);
    assertThat(send(gateway, "GET", "/docs/hotel", null, null, null).statusCode()).isEqualTo(200);
    assertThat(send(hotel, "GET", "/swagger-ui/index.html", null, null, null).statusCode())
        .isEqualTo(200);
    assertThat(send(booking, "GET", "/swagger-ui/index.html", null, null, null).statusCode())
        .isEqualTo(200);
  }

  @Test
  @Order(2)
  void twentyParallelBookingsHaveExactlyOneConfirmed() throws Exception {
    try (var pool = Executors.newFixedThreadPool(20)) {
      var gate = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return send(
                      gateway,
                      "POST",
                      "/booking",
                      alice,
                      UUID.randomUUID().toString(),
                      body(3, start.plusDays(10)));
                }));
      gate.countDown();
      int confirmed = 0;
      for (var f : futures) {
        var r = f.get(40, TimeUnit.SECONDS);
        assertThat(r.statusCode()).withFailMessage(r.body()).isIn(201, 409);
        if (r.statusCode() == 201) confirmed++;
      }
      assertThat(confirmed).isEqualTo(1);
    }
  }

  @Test
  @Order(3)
  void replyLostAfterHotelCommitIsEventuallyCompensated() throws Exception {
    loseReplies.set(true);
    String key = UUID.randomUUID().toString();
    var result = send(gateway, "POST", "/booking", alice, key, body(5, start.plusDays(20)));
    loseReplies.set(false);
    assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(504);
    long id = json.readTree(result.body()).path("bookingId").asLong();
    assertThat(id).isPositive();
    var booking = send(gateway, "GET", "/booking/" + id, alice, null, null);
    assertThat(json.readTree(booking.body()).path("status").asText()).isEqualTo("CANCELLED");
    await(
        () -> {
          try {
            var r =
                send(
                    gateway,
                    "GET",
                    "/api/rooms?hotelId=1&startDate="
                        + start.plusDays(20)
                        + "&endDate="
                        + start.plusDays(22),
                    alice,
                    null,
                    null);
            return json.readTree(r.body()).path("content").findValues("id").stream()
                .anyMatch(v -> v.asLong() == 5);
          } catch (Exception e) {
            return false;
          }
        },
        20);
    assertThat(
            send(gateway, "POST", "/booking", alice, key, body(5, start.plusDays(20))).statusCode())
        .isEqualTo(504);
  }

  @Test
  @Order(4)
  void parallelAutomaticBookingsOnDifferentDatesStayBalanced() throws Exception {
    String query =
        "/api/rooms/recommend?hotelId=1&startDate="
            + start.plusDays(500)
            + "&endDate="
            + start.plusDays(502);
    var before = send(gateway, "GET", query, alice, null, null);
    long totalBefore =
        json.readTree(before.body()).path("content").findValues("timesBooked").stream()
            .mapToLong(JsonNode::asLong)
            .sum();
    try (var executor = Executors.newFixedThreadPool(12)) {
      var go = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 12; i++) {
        final LocalDate date = start.plusDays(100 + i * 3L);
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  String body =
                      "{\"autoSelect\":true,\"hotelId\":1,\"startDate\":\""
                          + date
                          + "\",\"endDate\":\""
                          + date.plusDays(2)
                          + "\"}";
                  return send(
                      gateway, "POST", "/booking", alice, UUID.randomUUID().toString(), body);
                }));
      }
      go.countDown();
      for (var future : futures) {
        var response = future.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
      }
    }
    var after = send(gateway, "GET", query, alice, null, null);
    var counts =
        json.readTree(after.body()).path("content").findValues("timesBooked").stream()
            .mapToLong(JsonNode::asLong)
            .summaryStatistics();
    assertThat(counts.getSum()).isEqualTo(totalBefore + 12);
    assertThat(counts.getMax() - counts.getMin()).isLessThanOrEqualTo(1);
  }

  @AfterAll
  static void stop() {
    if (proxy != null) proxy.stop(0);
    for (Process p : processes.reversed()) {
      p.destroy();
      try {
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        p.destroyForcibly();
      }
    }
  }
}
