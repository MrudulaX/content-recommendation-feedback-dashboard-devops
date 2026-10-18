package com.crfd.dashboard.domain;

/**
 * Machine-readable classification of an exception detected by the feedback pipeline.
 * Used for grouping and for alert routing rules.
 */
public enum AlertCode {
    /** Recommender served a recommendation with a confidence score below threshold. */
    LOW_CONFIDENCE,
    /** End-to-end processing latency exceeded the SLA for the surface. */
    HIGH_LATENCY,
    /** Users are dismissing the same item repeatedly - likely a relevance defect. */
    HIGH_DISMISS_RATE,
    /** An event with this eventId was already ingested. */
    DUPLICATE_EVENT,
    /** Payload failed structural / business validation. */
    SCHEMA_MISMATCH,
    /** Event arrived with a client timestamp far in the past - possible backlog flush. */
    STALE_EVENT,
    /** Average user rating for an item dropped below the accepted floor. */
    RATING_DROP
}
