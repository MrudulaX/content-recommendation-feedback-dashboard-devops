package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.Alert;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AuditEntry;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationEvent;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.AuditEntryRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.web.dto.BulkEventResponse;
import com.crfd.dashboard.web.dto.EventDetailResponse;
import com.crfd.dashboard.web.dto.EventRequest;
import com.crfd.dashboard.web.dto.EventResponse;
import com.crfd.dashboard.web.dto.EventSearchCriteria;
import com.crfd.dashboard.web.dto.StatusUpdateRequest;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Data / event entry (CRFD-101), searchable dashboard (CRFD-102) and status drill-down (CRFD-104).
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** Whitelist of sortable properties - prevents arbitrary property injection through {@code sort=}. */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "processedAt", "status", "eventType", "source", "surface",
            "score", "latencyMs", "itemId", "userId", "eventId");

    private final RecommendationEventRepository events;
    private final AuditEntryRepository audits;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final PipelineScheduler pipeline;
    private final CrfdProperties properties;

    public EventService(RecommendationEventRepository events,
                        AuditEntryRepository audits,
                        AlertRepository alertRepository,
                        AlertService alertService,
                        PipelineScheduler pipeline,
                        CrfdProperties properties) {
        this.events = events;
        this.audits = audits;
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.pipeline = pipeline;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ ingestion

    @Transactional
    public EventResponse ingest(EventRequest request) {
        String eventId = (request.eventId() == null || request.eventId().isBlank())
                ? java.util.UUID.randomUUID().toString()
                : request.eventId().trim();

        if (events.existsByEventId(eventId)) {
            // Alert first (own transaction), then reject: the duplicate is the evidence we care about.
            alertService.raise(eventId, AlertCode.DUPLICATE_EVENT, AlertSeverity.WARNING,
                    "Event " + eventId + " was ingested more than once; the replay was rejected.");
            throw new DuplicateEventException(eventId);
        }

        RecommendationEvent event = new RecommendationEvent();
        event.setEventId(eventId);
        event.setCorrelationId(request.correlationId() != null ? request.correlationId() : eventId);
        event.setUserId(request.userId());
        event.setItemId(trimToNull(request.itemId()));
        event.setItemTitle(trimToNull(request.itemTitle()));
        event.setSource(request.source() != null ? request.source() : RecommendationSource.HYBRID);
        event.setSurface(request.surface() != null ? request.surface() : Surface.HOME_FEED);
        event.setEventType(request.eventType());
        event.setScore(request.score());
        event.setRating(request.rating());
        event.setComment(trimToNull(request.comment()));
        event.setClientTimestamp(request.clientTimestamp() != null ? request.clientTimestamp() : Instant.now());

        // ---- semantic gate: well formed JSON, unusable content -> QUARANTINED, never dropped ----
        if (event.getItemId() == null) {
            event.setStatus(EventStatus.QUARANTINED);
            String reason = "Rejected: itemId is required to attribute recommendation feedback.";
            event.setMessage(reason);
            event.setProcessedAt(Instant.now());
            RecommendationEvent saved = events.save(event);
            audits.save(new AuditEntry(eventId, null, EventStatus.QUARANTINED, reason, "ingest-api"));
            alertService.raise(eventId, AlertCode.SCHEMA_MISMATCH, AlertSeverity.CRITICAL,
                    "Event " + eventId + " quarantined: missing itemId.");
            log.warn("Event {} quarantined: {}", eventId, reason);
            return toResponse(saved);
        }

        event.setStatus(EventStatus.RECEIVED);
        RecommendationEvent saved = events.save(event);
        audits.save(new AuditEntry(eventId, null, EventStatus.RECEIVED,
                "Event accepted by the ingestion API", "ingest-api"));

        long staleHours = properties.getThresholds().getStaleEventHours();
        if (event.getClientTimestamp().isBefore(Instant.now().minus(staleHours, ChronoUnit.HOURS))) {
            alertService.raise(eventId, AlertCode.STALE_EVENT, AlertSeverity.INFO,
                    "Event %s arrived with a client timestamp older than %d hours - possible backlog flush"
                            .formatted(eventId, staleHours));
        }

        schedulePipeline(saved.getEventId());
        return toResponse(saved);
    }

    @Transactional
    public BulkEventResponse ingestBulk(List<EventRequest> requests) {
        int accepted = 0;
        int rejected = 0;
        List<EventResponse> acceptedEvents = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (EventRequest request : requests) {
            try {
                acceptedEvents.add(ingest(request));
                accepted++;
            } catch (DuplicateEventException e) {
                rejected++;
                errors.add(e.getMessage());
            }
        }
        log.info("Bulk ingestion finished: accepted={} rejected={}", accepted, rejected);
        return new BulkEventResponse(accepted, rejected, acceptedEvents, errors);
    }

    // ------------------------------------------------------------------ search (CRFD-102)

    @Transactional(readOnly = true)
    public Page<EventResponse> search(EventSearchCriteria criteria) {
        Pageable pageable = PageRequest.of(criteria.getPage(), criteria.getSize(), resolveSort(criteria.getSort()));
        return events.findAll(toSpecification(criteria), pageable).map(EventService::toResponse);
    }

    // ------------------------------------------------------------------ drill-down (CRFD-104)

    @Transactional(readOnly = true)
    public EventDetailResponse detail(String eventId) {
        RecommendationEvent event = events.findByEventId(eventId)
                .orElseThrow(() -> NotFoundException.event(eventId));
        List<EventDetailResponse.AuditView> timeline = audits.findByEventRefOrderByCreatedAtAscIdAsc(eventId).stream()
                .map(a -> new EventDetailResponse.AuditView(
                        a.getActor(),
                        a.getFromStatus() == null ? "NEW" : a.getFromStatus().name(),
                        a.getToStatus().name(),
                        a.getNote(),
                        a.getCreatedAt()))
                .toList();
        return new EventDetailResponse(toResponse(event), timeline, alertService.forEvent(eventId));
    }

    @Transactional(readOnly = true)
    public EventResponse get(String eventId) {
        return toResponse(events.findByEventId(eventId).orElseThrow(() -> NotFoundException.event(eventId)));
    }

    @Transactional
    public EventResponse updateStatus(String eventId, StatusUpdateRequest request) {
        RecommendationEvent event = events.findByEventId(eventId)
                .orElseThrow(() -> NotFoundException.event(eventId));
        EventStatus from = event.getStatus();
        String actor = (request.actor() == null || request.actor().isBlank()) ? "operator" : request.actor();
        String note = (request.note() == null || request.note().isBlank())
                ? "Manual status change by " + actor
                : request.note();

        event.setStatus(request.status());
        event.setMessage(note);
        if (request.status() == EventStatus.PROCESSED
                || request.status() == EventStatus.FAILED
                || request.status() == EventStatus.QUARANTINED) {
            event.setProcessedAt(Instant.now());
        }
        RecommendationEvent saved = events.save(event);
        audits.save(new AuditEntry(eventId, from, request.status(), note, actor));
        log.info("Event {} transitioned {} -> {} by {}", eventId, from, request.status(), actor);
        return toResponse(saved);
    }

    /** Requeue a FAILED or QUARANTINED event through the enrichment pipeline. */
    @Transactional
    public EventResponse reprocess(String eventId) {
        RecommendationEvent event = events.findByEventId(eventId)
                .orElseThrow(() -> NotFoundException.event(eventId));
        EventStatus previous = event.getStatus();
        EventStatus from = previous;
        event.setStatus(EventStatus.RECEIVED);
        event.setMessage("Requeued for enrichment by an operator");
        event.setProcessedAt(null);
        RecommendationEvent saved = events.save(event);
        audits.save(new AuditEntry(eventId, from, EventStatus.RECEIVED,
                "Operator triggered reprocessing", "operator"));
        log.info("Event {} requeued from {} to RECEIVED", eventId, previous);
        pipeline.schedule(eventId);
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ helpers

    private void schedulePipeline(String eventId) {
        pipeline.schedule(eventId);
    }

    private Specification<RecommendationEvent> toSpecification(EventSearchCriteria c) {
        Specification<RecommendationEvent> spec = (root, query, cb) -> cb.conjunction();

        if (c.getStatus() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), c.getStatus()));
        }
        if (c.getSource() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), c.getSource()));
        }
        if (c.getSurface() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("surface"), c.getSurface()));
        }
        if (c.getEventType() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("eventType"), c.getEventType()));
        }
        if (c.getUserId() != null && !c.getUserId().isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), c.getUserId().trim()));
        }
        if (c.getItemId() != null && !c.getItemId().isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("itemId"), c.getItemId().trim()));
        }
        if (c.getFrom() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), c.getFrom()));
        }
        if (c.getTo() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), c.getTo()));
        }
        if (c.getMinScore() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("score"), c.getMinScore()));
        }
        if (c.getMaxScore() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("score"), c.getMaxScore()));
        }
        if (c.getQ() != null && !c.getQ().isBlank()) {
            String like = "%" + c.getQ().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("eventId")), like),
                    cb.like(cb.lower(root.get("correlationId")), like),
                    cb.like(cb.lower(root.get("userId")), like),
                    cb.like(cb.lower(root.get("itemId")), like),
                    cb.like(cb.lower(root.get("itemTitle")), like),
                    cb.like(cb.lower(root.get("message")), like)));
        }
        if (Boolean.TRUE.equals(c.getOnlyWithAlerts())) {
            spec = spec.and((root, query, cb) -> {
                Subquery<String> subquery = query.subquery(String.class);
                Root<Alert> alert = subquery.from(Alert.class);
                subquery.select(alert.get("eventRef"));
                return root.get("eventId").in(subquery);
            });
        }
        return spec;
    }

    private Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!SORTABLE.contains(property)) {
            property = "createdAt";
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static EventResponse toResponse(RecommendationEvent e) {
        return new EventResponse(e.getId(), e.getEventId(), e.getCorrelationId(), e.getUserId(),
                e.getItemId(), e.getItemTitle(), e.getSource(), e.getSurface(), e.getEventType(),
                e.getScore(), e.getRating(), e.getStatus(), e.getLatencyMs(), e.getMessage(),
                e.getComment(), e.getClientTimestamp(), e.getCreatedAt(), e.getProcessedAt());
    }
}
