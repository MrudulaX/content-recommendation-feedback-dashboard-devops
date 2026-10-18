package com.crfd.dashboard.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Payload backing the summary indicators strip of the dashboard (CRFD-103).
 * Everything is computed over a rolling window (default 24 hours).
 */
public record SummaryResponse(
        Instant generatedAt,
        Instant windowFrom,
        long windowHours,

        // --- volume / status ---
        long totalEvents,
        long receivedEvents,
        long processingEvents,
        long processedEvents,
        long failedEvents,
        long quarantinedEvents,
        double successRate,
        double failureRate,

        // --- engagement ---
        long impressions,
        long clicks,
        long purchases,
        long dismisses,
        double clickThroughRate,
        double dismissRate,

        // --- quality ---
        Double averageLatencyMs,
        Double averageRating,

        // --- exceptions ---
        long openAlerts,
        long acknowledgedAlerts,
        long resolvedAlerts,
        long criticalOpenAlerts,

        // --- distributions ---
        List<Metric> byStatus,
        List<Metric> bySource,
        List<Metric> bySurface,
        List<Metric> byEventType,
        List<ItemMetric> topDismissedItems,
        List<ItemMetric> topRecommendedItems
) {

    /** A labelled counter used for pie/bar charts. */
    public record Metric(String key, long count) {
    }

    /** An item id, its display title (may be null) and a count. */
    public record ItemMetric(String itemId, String itemTitle, long count) {
    }
}
