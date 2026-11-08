package com.example.hotel.booking;

import com.example.hotel.common.ApiException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Serializes automatic choices per hotel in the single-instance H2 deployment. Database room locks
 * remain responsible for preventing double reservations.
 */
@Component
public final class AutoSelectionCoordinator {
  // A fixed number of stripes avoids retaining arbitrary hotel ids forever.
  private final ReentrantLock[] stripes = new ReentrantLock[64];

  public AutoSelectionCoordinator() {
    for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock(true);
  }

  public Permit acquire(Long hotelId, long deadline) {
    if (hotelId == null) return () -> {};
    ReentrantLock lock = stripes[Math.floorMod(hotelId.hashCode(), stripes.length)];
    try {
      if (!lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
        throw new ApiException(504, "BOOKING_TIMEOUT", "Automatic selection deadline exceeded");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "INTERRUPTED", "Automatic selection interrupted");
    }
    return lock::unlock;
  }

  @FunctionalInterface
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }
}
