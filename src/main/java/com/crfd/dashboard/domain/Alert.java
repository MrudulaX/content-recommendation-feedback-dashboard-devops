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
 * An operational exception raised by the feedback pipeline or manually by an operator.
 * Drives the alert / exception view of the dashboard.
 */
@Entity
@Table(
        name = "alert",
        indexes = {
                @Index(name = "ix_alert_ref", columnList = "alert_ref", unique = true),
                @Index(name = "ix_alert_status", columnList = "status"),
                @Index(name = "ix_alert_event_ref", columnList = "event_ref"),
                @Index(name = "ix_alert_severity", columnList = "severity")
        })
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human friendly business key, e.g. ALT-4F9C1A2B. */
    @Column(name = "alert_ref", nullable = false, length = 32, unique = true)
    private String alertRef;

    /** eventId of the offending event, when the alert is event-scoped. */
    @Column(name = "event_ref", length = 64)
    private String eventRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16, nullable = false)
    private AlertSeverity severity = AlertSeverity.WARNING;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", length = 32, nullable = false)
    private AlertCode code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(name = "message", length = 512, nullable = false)
    private String message;

    /** Owning team / engineer once acknowledged. */
    @Column(name = "owner", length = 64)
    private String owner;

    @Column(name = "resolution_note", length = 512)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (alertRef == null || alertRef.isBlank()) {
            alertRef = "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        if (status == null) {
            status = AlertStatus.OPEN;
        }
        if (severity == null) {
            severity = AlertSeverity.WARNING;
        }
    }

    // ------------------------------------------------------------------ getters / setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAlertRef() { return alertRef; }
    public void setAlertRef(String alertRef) { this.alertRef = alertRef; }

    public String getEventRef() { return eventRef; }
    public void setEventRef(String eventRef) { this.eventRef = eventRef; }

    public AlertSeverity getSeverity() { return severity; }
    public void setSeverity(AlertSeverity severity) { this.severity = severity; }

    public AlertCode getCode() { return code; }
    public void setCode(AlertCode code) { this.code = code; }

    public AlertStatus getStatus() { return status; }
    public void setStatus(AlertStatus status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
