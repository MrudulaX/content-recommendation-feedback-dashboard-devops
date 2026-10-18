package com.crfd.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single recommendation feedback event - the primary record of the dashboard.
 *
 * <p>This is deliberately a single wide table for the MVP: it keeps writes cheap
 * (high ingest rate) and makes the searchable dashboard a single, index-friendly query.
 * See {@code docs/16-limitations-and-future-enhancements.md} for the planned
 * normalisation / event-store split.</p>
 */
@Entity
@Table(
        name = "recommendation_event",
        indexes = {
                @Index(name = "ix_event_event_id", columnList = "event_id", unique = true),
                @Index(name = "ix_event_status", columnList = "status"),
                @Index(name = "ix_event_created_at", columnList = "created_at"),
                @Index(name = "ix_event_item", columnList = "item_id"),
                @Index(name = "ix_event_source", columnList = "source")
        })
public class RecommendationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key supplied by the producer; unique, used for deduplication. */
    @Column(name = "event_id", nullable = false, length = 64, unique = true)
    private String eventId;

    /** Groups all events belonging to one user session / request for tracing. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Pseudonymised user identifier - never a raw e-mail or customer id. */
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "item_id", length = 64)
    private String itemId;

    @Column(name = "item_title", length = 256)
    private String itemTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 32)
    private RecommendationSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "surface", length = 32)
    private Surface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private EventType eventType;

    /** Model confidence for the served recommendation, 0.0 - 1.0. */
    @Column(name = "score")
    private Double score;

    /** Optional 1-5 star rating supplied by the user for a RATING event. */
    @Column(name = "rating")
    private Integer rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EventStatus status = EventStatus.RECEIVED;

    /** Wall-clock processing time in milliseconds, filled by the pipeline. */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** Human readable outcome / failure reason shown during drill-down. */
    @Column(name = "message", length = 512)
    private String message;

    /** Optional free-text user comment attached to a rating. */
    @Column(name = "comment", length = 512)
    private String comment;

    /** Timestamp as reported by the producing client. */
    @Column(name = "client_timestamp")
    private Instant clientTimestamp;

    /** Timestamp the API accepted the event. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Timestamp the pipeline finished with the event. */
    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (eventId == null || eventId.isBlank()) {
            eventId = newEventId();
        }
        if (status == null) {
            status = EventStatus.RECEIVED;
        }
    }

    /**
     * Mints a human-friendly business key for an event that arrived without one, e.g.
     * {@code EVT-7F3A91C4}.
     *
     * <p>Events are identified by this key wherever a person sees them - the searchable
     * table, the status drill-down, alert messages - so it has to be readable. It is
     * deliberately the same shape as the {@code ALT-} and {@code FB-} keys minted for
     * alerts and explicit feedback, and as the keys the traffic simulator produces:
     * {@code PREFIX-} followed by eight uppercase hexadecimal characters.</p>
     *
     * <p>The hyphens are stripped before truncating. A UUID's hyphen sits at index 8, so
     * {@code toString().substring(0, 8)} happens to be clean but any longer prefix is not
     * - a 12-character slice yields {@code EVT-5794A34B-98B}, which reads like a truncated
     * UUID rather than a business key. Stripping first means the key has the documented
     * shape at any length, which is what the contract tests assert.</p>
     */
    public static String newEventId() {
        return "EVT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    // ------------------------------------------------------------------ getters / setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public String getItemTitle() { return itemTitle; }
    public void setItemTitle(String itemTitle) { this.itemTitle = itemTitle; }

    public RecommendationSource getSource() { return source; }
    public void setSource(RecommendationSource source) { this.source = source; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Instant getClientTimestamp() { return clientTimestamp; }
    public void setClientTimestamp(Instant clientTimestamp) { this.clientTimestamp = clientTimestamp; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
