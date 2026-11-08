package com.example.hotel.booking;

import com.example.hotel.booking.BookingDtos.*;
import com.example.hotel.common.*;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class HotelClient implements HotelOperations {
  private final RestClient client;
  private final HttpClient transport;
  private final Duration readTimeout;
  private final TokenService tokens;
  private final CircuitBreaker breaker;
  private final long delay;
  private final MeterRegistry metrics;

  @Configuration
  static class Clients {
    @Bean
    @LoadBalanced
    RestClient.Builder hotelRestBuilder() {
      return RestClient.builder();
    }
  }

  public HotelClient(
      RestClient.Builder builder,
      TokenService tokens,
      @Value("${booking.hotel-url:}") String url,
      @Value("${booking.http.connect-ms:500}") int connect,
      @Value("${booking.http.read-ms:1500}") int read,
      @Value("${booking.http.retry-delay-ms:200}") long delay,
      MeterRegistry metrics) {
    this.tokens = tokens;
    this.delay = delay;
    this.metrics = metrics;
    transport =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connect))
            .build();
    readTimeout = Duration.ofMillis(read);
    var factory = new JdkClientHttpRequestFactory(transport);
    factory.setReadTimeout(readTimeout);
    client =
        (url.isBlank() ? builder : RestClient.builder())
            .requestFactory(factory)
            .baseUrl(url.isBlank() ? "http://hotel-service" : url)
            .build();
    breaker =
        CircuitBreaker.of(
            "hotel",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .recordException(
                    e ->
                        e instanceof ResourceAccessException
                            || e instanceof HttpServerErrorException)
                .build());
    io.micrometer.core.instrument.Gauge.builder(
            "hotel.circuit.state", breaker, b -> b.getState().getOrder())
        .register(metrics);
  }

  public List<RoomView> recommend(CreateBooking q, String userToken, long deadline) {
    return recommendations(q.hotelId(), q.startDate(), q.endDate(), 0, 100, userToken, deadline)
        .content();
  }

  public PageResult<RoomView> recommendations(
      Long hotelId,
      LocalDate start,
      LocalDate end,
      int page,
      int size,
      String userToken,
      long deadline) {
    return call(
        () ->
            client(deadline)
                .get()
                .uri(
                    u ->
                        u.path("/api/rooms/recommend")
                            .queryParamIfPresent("hotelId", Optional.ofNullable(hotelId))
                            .queryParam("startDate", start)
                            .queryParam("endDate", end)
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                .headers(
                    h -> {
                      h.setBearerAuth(userToken);
                      h.set("X-Correlation-Id", trace());
                    })
                .retrieve()
                .body(new ParameterizedTypeReference<PageResult<RoomView>>() {}),
        deadline);
  }

  public RoomView room(Long roomId, String userToken, long deadline) {
    return call(
        () ->
            client(deadline)
                .get()
                .uri("/api/rooms/{id}", roomId)
                .headers(
                    headers -> {
                      headers.setBearerAuth(userToken);
                      headers.set("X-Correlation-Id", trace());
                    })
                .retrieve()
                .body(RoomView.class),
        deadline);
  }

  private RestClient client(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new ApiException(504, "HOTEL_TIMEOUT", "Booking deadline exceeded");
    var factory = new JdkClientHttpRequestFactory(transport);
    factory.setReadTimeout(Duration.ofNanos(Math.min(remaining, readTimeout.toNanos())));
    return client.mutate().requestFactory(factory).build();
  }

  public void confirm(BookingAttempt attempt, long deadline) {
    operation(attempt, "confirm-availability", deadline);
  }

  public void release(BookingAttempt attempt) {
    operation(attempt, "release", System.nanoTime() + Duration.ofSeconds(10).toNanos());
  }

  private void operation(BookingAttempt a, String operation, long deadline) {
    call(
        () -> {
          client(deadline)
              .post()
              .uri("/api/rooms/{id}/" + operation, a.getRoomId())
              .headers(
                  h -> {
                    h.setBearerAuth(tokens.service());
                    h.set("X-Correlation-Id", trace());
                    h.set("X-Request-Id", a.getId().toString());
                  })
              .body(a.request())
              .retrieve()
              // A deleted/nonexistent room cannot retain a reservation: deletion
              // is forbidden whenever a reservation record exists.
              .onStatus(
                  status -> operation.equals("release") && status.value() == 404,
                  (request, response) -> {})
              .toBodilessEntity();
          return true;
        },
        deadline);
  }

  private String trace() {
    return Optional.ofNullable(MDC.get("traceId")).orElseGet(() -> UUID.randomUUID().toString());
  }

  private <T> T call(Supplier<T> request, long deadline) {
    RuntimeException last = null;
    for (int i = 0; i < 3; i++) {
      if (System.nanoTime() >= deadline)
        throw new ApiException(504, "HOTEL_TIMEOUT", "Booking deadline exceeded");
      try {
        T response = breaker.executeSupplier(request);
        if (System.nanoTime() >= deadline)
          throw new ApiException(504, "HOTEL_TIMEOUT", "Booking deadline exceeded");
        return response;
      } catch (HttpClientErrorException e) {
        if (e.getStatusCode().value() == 409) throw ApiException.conflict("Room unavailable");
        if (e.getStatusCode().value() == 404) throw ApiException.missing();
        if (e.getStatusCode().value() == 400)
          throw new ApiException(400, "HOTEL_BAD_REQUEST", "Invalid hotel request");
        throw new ApiException(503, "HOTEL_REJECTED", "Hotel rejected service request");
      } catch (CallNotPermittedException e) {
        throw new ApiException(503, "HOTEL_CIRCUIT_OPEN", "Hotel circuit breaker is open");
      } catch (ResourceAccessException | HttpServerErrorException e) {
        org.slf4j.LoggerFactory.getLogger(HotelClient.class)
            .warn("Hotel transient failure attempt={} error={}", i + 1, e.toString());
        last = e;
        metrics.counter("hotel.remote.errors").increment();
        if (i < 2) {
          metrics.counter("hotel.remote.retries").increment();
          long pause = delay * (1L << i);
          if (System.nanoTime() + Duration.ofMillis(pause).toNanos() >= deadline) break;
          try {
            Thread.sleep(pause);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "INTERRUPTED", "Remote call interrupted");
          }
        }
      }
    }
    boolean timeout = System.nanoTime() >= deadline;
    for (Throwable e = last; e != null; e = e.getCause())
      if (e instanceof java.net.http.HttpTimeoutException
          || e instanceof java.net.SocketTimeoutException
          || e instanceof java.util.concurrent.TimeoutException) timeout = true;
    throw new ApiException(
        timeout ? 504 : 503,
        timeout ? "HOTEL_TIMEOUT" : "HOTEL_UNAVAILABLE",
        "Hotel call failed after bounded retries");
  }
}
