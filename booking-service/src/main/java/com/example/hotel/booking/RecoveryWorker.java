package com.example.hotel.booking;

import io.micrometer.core.instrument.*;
import java.time.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "booking.recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RecoveryWorker {
  private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);
  private final BookingRepository bookings;
  private final CompensationRepository tasks;
  private final AttemptRepository attempts;
  private final BookingStore store;
  private final HotelOperations hotel;
  private final long stale;
  private final MeterRegistry metrics;

  public RecoveryWorker(
      BookingRepository b,
      CompensationRepository t,
      AttemptRepository a,
      BookingStore s,
      HotelOperations h,
      @Value("${booking.pending-timeout-seconds:30}") long stale,
      MeterRegistry metrics) {
    bookings = b;
    tasks = t;
    attempts = a;
    store = s;
    hotel = h;
    this.stale = stale;
    this.metrics = metrics;
    Gauge.builder("booking.compensations.pending", tasks, r -> r.countByState("READY"))
        .register(metrics);
  }

  @Scheduled(
      fixedDelayString = "${booking.recovery-interval-ms:1000}",
      initialDelayString = "${booking.recovery-initial-delay-ms:1000}")
  public void recover() {
    for (var b :
        bookings.findByStatusAndCreatedAtBefore(
            Booking.Status.PENDING, Instant.now().minusSeconds(stale))) {
      MDC.put("traceId", b.getTraceId());
      MDC.put("bookingId", b.getId().toString());
      try {
        store.failed(b.getId(), 504, "STALE_PENDING");
      } finally {
        MDC.clear();
      }
    }
    for (var t :
        tasks.findTop20ByStateAndNextAttemptAtBeforeOrderByNextAttemptAt("READY", Instant.now())) {
      var b = bookings.findById(t.getBookingId()).orElseThrow();
      MDC.put("traceId", b.getTraceId());
      MDC.put("bookingId", b.getId().toString());
      try {
        hotel.release(attempts.findById(t.getId()).orElseThrow());
        store.taskDone(t.getId());
        metrics.counter("booking.compensations.completed").increment();
        log.info("compensation completed operationId={}", t.getId());
      } catch (Exception e) {
        store.taskFailed(t.getId(), e);
        log.warn(
            "compensation scheduled for retry operationId={} error={}", t.getId(), e.toString());
      } finally {
        MDC.clear();
      }
    }
  }
}
