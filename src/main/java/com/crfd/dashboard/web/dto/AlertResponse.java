package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;

import java.time.Instant;

/** One row in the alert / exception view (CRFD-105). */
public record AlertResponse(
        Long id,
        String alertRef,
        String eventRef,
        AlertSeverity severity,
        AlertCode code,
        AlertStatus status,
        String message,
        String owner,
        String resolutionNote,
        Instant createdAt,
        Instant acknowledgedAt,
        Instant resolvedAt
) {
}
