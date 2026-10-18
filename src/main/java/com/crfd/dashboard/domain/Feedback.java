package com.crfd.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit, semantically rich feedback captured from the end user about a
 * recommendation (thumbs up/down, star rating, reason text). Kept separate from the
 * high-volume behavioural event stream because it is low volume but high value.
 */
@Entity
@Table(
        name = "recommendation_feedback",
        indexes = {
                @Index(name = "ix_feedback_ref", columnList = "feedback_ref", unique = true),
                @Index(name = "ix_feedback_item", columnList = "item_id"),
                @Index(name = "ix_feedback_created_at", columnList = "created_at")
        })
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_ref", nullable = false, length = 40, unique = true)
    private String feedbackRef;

    /** Optional link back to the behavioural event being rated. */
    @Column(name = "event_ref", length = 64)
    private String eventRef;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "item_id", length = 64, nullable = false)
    private String itemId;

    /** 1 (very poor) to 5 (excellent). */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    /** Coded reason, e.g. NOT_RELEVANT, ALREADY_SEEN, IRRELEVANT_PRICE, POOR_QUALITY. */
    @Column(name = "reason", length = 48)
    private String reason;

    @Column(name = "comment", length = 512)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (feedbackRef == null || feedbackRef.isBlank()) {
            feedbackRef = "FB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFeedbackRef() { return feedbackRef; }
    public void setFeedbackRef(String feedbackRef) { this.feedbackRef = feedbackRef; }

    public String getEventRef() { return eventRef; }
    public void setEventRef(String eventRef) { this.eventRef = eventRef; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
