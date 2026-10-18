package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AuditEntry;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationEvent;
import com.crfd.dashboard.repository.AuditEntryRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The feedback enrichment pipeline (CRFD-104).
 *
 * <p>Runs asynchronously on the dedicated {@code eventExecutor} so that the ingestion API
 * stays fast (target: p95 write under 50 ms) while validation, anomaly detection and metric
 * enrichment happen out of band. Every state change is recorded in {@code event_audit},
 * which is what makes the status drill-down meaningful.</p>
 *
 * <p>Detection rules implemented here (thresholds come from {@link CrfdProperties}):
 * <ul>
 *   <li>{@code SCHEMA_MISMATCH}   - a served IMPRESSION without a confidence score</li>
 *   <li>{@code LOW_CONFIDENCE}    - score below the confidence floor</li>
 *   <li>{@code HIGH_LATENCY}      - processing latency over the warning/critical SLA</li>
 *   <li>{@code HIGH_DISMISS_RATE} - the same item dismissed repeatedly in the window</li>
 *   <li>{@code RATING_DROP}       - explicit low rating attached to the event</li>
 * </ul>
 */
@Service
public class EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

    private final RecommendationEventRepository events;
    private final AuditEntryRepository audits;
    private final AlertService alertService;
    private final CrfdProperties properties;

    public EventProcessingService(RecommendationEventRepository events,
                                  AuditEntryRepository audits,
                                  AlertService alertService,
                                  CrfdProperties properties) {
        this.events = events;
        this.audits = audits;
        this.alertService = alertService;
        this.properties = properties;
    }

    /**
     * Enrichment work unit.
     *
     * <p>{@code REQUIRES_NEW} is deliberate: the pipeline is very often dispatched from the
     * {@code afterCommit} callback of the ingest transaction, where the outer transaction has
     * already committed but its resources are still bound to the thread. Joining that dead
     * transaction would silently discard every write; suspending it and starting a fresh one
     * makes the pipeline's persistence independent of the ingest transaction.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(String eventId) {
        RecommendationEvent event = events.findByEventId(eventId).orElse(null);
        if (event == null) {
            log.warn("Pipeline invoked for unknown event {}", eventId);
            return;
        }
        if (event.getStatus() != EventStatus.RECEIVED) {
            log.debug("Event {} already in state {}, skipping pipeline", eventId, event.getStatus());
            return;
        }

        transition(event, EventStatus.PROCESSING, "Pipeline picked up the event for enrichment", "crfd-pipeline");

        long startedAt = System.currentTimeMillis();
        long simulated = simulateEnrichmentWork();
        long reportedLatency = simulated + (System.currentTimeMillis() - startedAt);
        event.setLatencyMs(reportedLatency);

        EventStatus outcome = EventStatus.PROCESSED;
        String message = "Processed: validators passed and metrics updated.";

        var thresholds = properties.getThresholds();

        // --- Rule 1: a served impression must carry a confidence score ---
        if (event.getEventType() == EventType.IMPRESSION && event.getScore() == null) {
            outcome = EventStatus.FAILED;
            message = "Rejected: served impression without a confidence score";
            alertService.raise(event.getEventId(), AlertCode.SCHEMA_MISMATCH, AlertSeverity.CRITICAL,
                    "Impression " + event.getEventId() + " for item " + event.getItemId()
                            + " arrived without a confidence score; cannot evaluate relevance.");
        }

        // --- Rule 2: low model confidence ---
        if (event.getScore() != null && event.getScore() < thresholds.getLowConfidence()) {
            alertService.raise(event.getEventId(), AlertCode.LOW_CONFIDENCE, AlertSeverity.WARNING,
                    "Confidence %.2f for item %s is below the floor of %.2f"
                            .formatted(event.getScore(), event.getItemId(), thresholds.getLowConfidence()));
        }

        // --- Rule 3: latency SLA ---
        if (reportedLatency > thresholds.getCriticalLatencyMs()) {
            alertService.raise(event.getEventId(), AlertCode.HIGH_LATENCY, AlertSeverity.CRITICAL,
                    "Processing latency %d ms exceeds the critical SLA of %d ms"
                            .formatted(reportedLatency, thresholds.getCriticalLatencyMs()));
        } else if (reportedLatency > thresholds.getHighLatencyMs()) {
            alertService.raise(event.getEventId(), AlertCode.HIGH_LATENCY, AlertSeverity.WARNING,
                    "Processing latency %d ms exceeds the warning SLA of %d ms"
                            .formatted(reportedLatency, thresholds.getHighLatencyMs()));
        }

        // --- Rule 4: repeated dismissals of the same item ---
        if (event.getEventType() == EventType.DISMISS && event.getItemId() != null) {
            Instant since = Instant.now().minus(thresholds.getDismissWindowMinutes(), ChronoUnit.MINUTES);
            long dismissals = events
                    .findByItemIdAndEventTypeAndCreatedAtAfter(event.getItemId(), EventType.DISMISS, since)
                    .size();
            if (dismissals >= thresholds.getDismissRateThreshold()) {
                alertService.raise(event.getEventId(), AlertCode.HIGH_DISMISS_RATE, AlertSeverity.CRITICAL,
                        "Item %s was dismissed %d times in the last %d minutes"
                                .formatted(event.getItemId(), dismissals, thresholds.getDismissWindowMinutes()));
            }
        }

        // --- Rule 5: explicit low rating travelling with the event ---
        if (event.getRating() != null && event.getRating() <= thresholds.getLowRatingThreshold()) {
            alertService.raise(event.getEventId(), AlertCode.RATING_DROP, AlertSeverity.WARNING,
                    "User rated item %s %d/5 - relevance review required"
                            .formatted(event.getItemId(), event.getRating()));
        }

        event.setMessage(message);
        transition(event, outcome, message, "crfd-pipeline");
    }

    /** Simulates the wall-clock cost of enrichment; keeps the latency distribution realistic. */
    private long simulateEnrichmentWork() {
        var processing = properties.getProcessing();
        long latency = processing.getBaseLatencyMs()
                + (long) (ThreadLocalRandom.current().nextDouble() * processing.getJitterMs());
        if (ThreadLocalRandom.current().nextDouble() < processing.getSpikeProbability()) {
            latency += processing.getSpikeLatencyMs();
        }
        try {
            Thread.sleep(Math.min(latency, 60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return latency;
    }

    private void transition(RecommendationEvent event, EventStatus to, String note, String actor) {
        EventStatus from = event.getStatus();
        event.setStatus(to);
        event.setMessage(note);
        if (to == EventStatus.PROCESSED || to == EventStatus.FAILED || to == EventStatus.QUARANTINED) {
            event.setProcessedAt(Instant.now());
        }
        events.save(event);
        audits.save(new AuditEntry(event.getEventId(), from, to, note, actor));
    }
}
