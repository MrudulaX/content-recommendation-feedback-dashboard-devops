package com.crfd.dashboard.domain;

/** Lifecycle state of a single recommendation feedback event. */
public enum EventStatus {
    /** Accepted by the API, waiting to be processed. */
    RECEIVED,
    /** Currently being enriched / validated by the feedback pipeline. */
    PROCESSING,
    /** Fully processed, metrics updated. */
    PROCESSED,
    /** Processing failed irrecoverably; an alert has been raised. */
    FAILED,
    /** Payload was structurally invalid; parked for manual inspection. */
    QUARANTINED
}
