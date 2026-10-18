package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.Alert;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.web.dto.AlertDecisionRequest;
import com.crfd.dashboard.web.dto.AlertResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Owns the alert / exception view (CRFD-105) and the alert lifecycle
 * OPEN → ACKNOWLEDGED → RESOLVED.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final CrfdProperties properties;

    public AlertService(AlertRepository alerts, CrfdProperties properties) {
        this.alerts = alerts;
        this.properties = properties;
    }

    /**
     * Raise an alert. Runs in its own transaction so that an alert is never rolled back by a
     * failing ingestion - diagnosing the failure must survive the failure itself.
     *
     * @return the created alert, or {@code null} when an identical OPEN alert already exists
     *         (idempotent: replayed events must not fan out into alert storms)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AlertResponse raise(String eventRef, AlertCode code, AlertSeverity severity, String message) {
        if (eventRef != null && alerts.existsByEventRefAndCodeAndStatus(eventRef, code, AlertStatus.OPEN)) {
            log.debug("Suppressing duplicate {} alert for event {}", code, eventRef);
            return null;
        }
        Alert alert = new Alert();
        alert.setAlertRef("ALT-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        alert.setEventRef(eventRef);
        alert.setCode(code);
        alert.setSeverity(severity);
        alert.setStatus(AlertStatus.OPEN);
        alert.setMessage(truncate(message, 500));
        Alert saved = alerts.save(alert);
        log.info("Alert raised [{}] {} event={} msg={}", severity, code, eventRef, saved.getMessage());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> search(AlertStatus status, AlertSeverity severity, AlertCode code,
                                      String q, Pageable pageable) {
        Specification<Alert> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (severity != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), severity));
        }
        if (code != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("code"), code));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("message")), like),
                    cb.like(cb.lower(root.get("alertRef")), like),
                    cb.like(cb.lower(root.get("eventRef")), like),
                    cb.like(cb.lower(root.get("owner")), like)));
        }
        return alerts.findAll(spec, pageable).map(AlertService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> forEvent(String eventRef) {
        return alerts.findByEventRefOrderByCreatedAtDesc(eventRef).stream()
                .map(AlertService::toResponse)
                .toList();
    }

    @Transactional
    public AlertResponse acknowledge(String alertRef, AlertDecisionRequest request) {
        Alert alert = require(alertRef);
        if (alert.getStatus() == AlertStatus.RESOLVED) {
            throw new IllegalStateException("Alert " + alertRef + " is already resolved");
        }
        alert.setStatus(AlertStatus.ACKNOWLEDGED);
        alert.setOwner(request.owner());
        alert.setAcknowledgedAt(Instant.now());
        if (request.note() != null && !request.note().isBlank()) {
            alert.setResolutionNote(request.note());
        }
        return toResponse(alerts.save(alert));
    }

    @Transactional
    public AlertResponse resolve(String alertRef, AlertDecisionRequest request) {
        Alert alert = require(alertRef);
        alert.setStatus(AlertStatus.RESOLVED);
        alert.setOwner(request.owner());
        if (alert.getAcknowledgedAt() == null) {
            alert.setAcknowledgedAt(Instant.now());
        }
        alert.setResolvedAt(Instant.now());
        alert.setResolutionNote(request.note() == null || request.note().isBlank()
                ? "Resolved by " + request.owner()
                : request.note());
        return toResponse(alerts.save(alert));
    }

    @Transactional(readOnly = true)
    public long count(AlertStatus status) {
        return status == null ? alerts.count() : alerts.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countOpenCritical() {
        return alerts.countByStatusAndSeverity(AlertStatus.OPEN, AlertSeverity.CRITICAL);
    }

    private Alert require(String alertRef) {
        return alerts.findByAlertRef(alertRef).orElseThrow(() -> NotFoundException.alert(alertRef));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public static AlertResponse toResponse(Alert a) {
        return new AlertResponse(a.getId(), a.getAlertRef(), a.getEventRef(), a.getSeverity(), a.getCode(),
                a.getStatus(), a.getMessage(), a.getOwner(), a.getResolutionNote(),
                a.getCreatedAt(), a.getAcknowledgedAt(), a.getResolvedAt());
    }
}
