package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Inbound payload for the data/event entry capability (CRFD-101).
 *
 * <p>Two layers of validation are intentional:
 * a syntactically broken payload is rejected with <b>400</b> by bean validation,
 * while a well formed but semantically unusable event (for example a missing
 * {@code itemId}) is accepted, marked {@code QUARANTINED} and surfaced as a
 * {@code SCHEMA_MISMATCH} alert so that the dashboard shows the exception instead of
 * silently dropping traffic.</p>
 */
public record EventRequest(

        @Size(max = 64, message = "eventId must be at most 64 characters")
        String eventId,

        @Size(max = 64)
        String correlationId,

        @NotBlank(message = "userId is required")
        @Size(max = 64)
        String userId,

        @Size(max = 64)
        String itemId,

        @Size(max = 256)
        String itemTitle,

        RecommendationSource source,

        Surface surface,

        @NotNull(message = "eventType is required")
        EventType eventType,

        @DecimalMin(value = "0.0", message = "score must be between 0.0 and 1.0")
        @DecimalMax(value = "1.0", message = "score must be between 0.0 and 1.0")
        Double score,

        @Min(1) @Max(5)
        Integer rating,

        @Size(max = 512)
        String comment,

        Instant clientTimestamp
) {
}
