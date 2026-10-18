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

/**
 * Immutable status-transition record for an event. Powers the "status drill-down"
 * timeline, and is the audit trail evidence for the alert lifecycle.
 */
@Entity
@Table(
        name = "event_audit",
        indexes = {@Index(name = "ix_audit_event_ref", columnList = "event_ref")})
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_ref", nullable = false, length = 64)
    private String eventRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private EventStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 16, nullable = false)
    private EventStatus toStatus;

    @Column(name = "note", length = 512)
    private String note;

    /** Who/what caused the transition: a component name or an operator id. */
    @Column(name = "actor", length = 64)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuditEntry() {
    }

    public AuditEntry(String eventRef, EventStatus fromStatus, EventStatus toStatus, String note, String actor) {
        this.eventRef = eventRef;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
        this.actor = actor;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventRef() { return eventRef; }
    public void setEventRef(String eventRef) { this.eventRef = eventRef; }

    public EventStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(EventStatus fromStatus) { this.fromStatus = fromStatus; }

    public EventStatus getToStatus() { return toStatus; }
    public void setToStatus(EventStatus toStatus) { this.toStatus = toStatus; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
