package com.example.hotel.common;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record ReservationRequest(
    @NotNull UUID operationId,
    @NotNull Long bookingId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate) {}
