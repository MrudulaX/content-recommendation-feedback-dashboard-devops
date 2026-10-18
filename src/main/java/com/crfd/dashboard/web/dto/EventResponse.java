package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;

import java.time.Instant;

/** Outbound representation of one feedback event. */
public record EventResponse(
        Long id,
        String eventId,
        String correlationId,
        String userId,
        String itemId,
        String itemTitle,
        RecommendationSource source,
        Surface surface,
        EventType eventType,
        Double score,
        Integer rating,
        EventStatus status,
        Long latencyMs,
        String message,
        String comment,
        Instant clientTimestamp,
        Instant createdAt,
        Instant processedAt
) {
}
