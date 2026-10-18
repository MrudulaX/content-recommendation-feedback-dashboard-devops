package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.Alert;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.domain.AuditEntry;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.Feedback;
import com.crfd.dashboard.domain.RecommendationEvent;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.AuditEntryRepository;
import com.crfd.dashboard.repository.FeedbackRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.web.dto.EventRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates realistic recommendation traffic.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #simulate(int)} - live traffic through the real ingestion API, exercising
 *       validation, the async pipeline and alert detection. Bound to the dashboard's
 *       "Generate traffic" button.</li>
 *   <li>{@link #seedHistory(int, int)} - backdated records written directly, so a fresh
 *       environment has a populated 48-hour trend line to demonstrate the dashboard
 *       without waiting. Only runs when {@code crfd.simulation.seed-on-startup=true}.</li>
 * </ul>
 */
@Service
public class TrafficSimulator {

    private record Item(String id, String title) {
    }

    private static final List<Item> CATALOGUE = List.of(
            new Item("ITM-1001", "Designing Data-Intensive Applications"),
            new Item("ITM-1002", "The Pragmatic Programmer"),
            new Item("ITM-1003", "Wireless Noise-Cancelling Headphones"),
            new Item("ITM-1004", "Standing Desk Converter"),
            new Item("ITM-1005", "Espresso Machine - Barista Edition"),
            new Item("ITM-1006", "Introduction to Machine Learning"),
            new Item("ITM-1007", "Mechanical Keyboard (75%)"),
            new Item("ITM-1008", "Ultrawide 34\" Monitor"),
            new Item("ITM-1009", "Trail Running Shoes"),
            new Item("ITM-1010", "Noise-Isolating Ear Buds"),
            new Item("ITM-1011", "Cold Brew Coffee Maker"),
            new Item("ITM-1012", "Ergonomic Mesh Chair"));

    private static final String[] REASONS = {"NOT_RELEVANT", "ALREADY_SEEN", "PRICE_TOO_HIGH", "POOR_QUALITY"};
    private static final RecommendationSource[] SOURCES = RecommendationSource.values();
    private static final Surface[] SURFACES = Surface.values();

    private final EventService eventService;
    private final RecommendationEventRepository events;
    private final AuditEntryRepository audits;
    private final AlertRepository alertRepository;
    private final FeedbackRepository feedbackRepository;
    private final CrfdProperties properties;

    public TrafficSimulator(EventService eventService,
                            RecommendationEventRepository events,
                            AuditEntryRepository audits,
                            AlertRepository alertRepository,
                            FeedbackRepository feedbackRepository,
                            CrfdProperties properties) {
        this.eventService = eventService;
        this.events = events;
        this.audits = audits;
        this.alertRepository = alertRepository;
        this.feedbackRepository = feedbackRepository;
        this.properties = properties;
    }

    /** Live traffic: goes through the real API path so alerts and audits are genuine. */
    public int simulate(int count) {
        int batch = Math.min(Math.max(1, count), properties.getSimulation().getMaxBatch());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < batch; i++) {
            try {
                eventService.ingest(randomRequest(random));
            } catch (RuntimeException e) {
                // A synthetic duplicate is a valid outcome - keep generating.
            }
        }
        return batch;
    }

    /** Backdated history for a freshly provisioned environment. */
    @Transactional
    public int seedHistory(int count, int hoursBack) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Instant now = Instant.now();
        int created = 0;
        for (int i = 0; i < count; i++) {
            Instant at = now.minusSeconds((long) (random.nextDouble() * hoursBack * 3600L));
            RecommendationEvent event = buildHistoricalEvent(random, at);
            events.save(event);
            audits.save(new AuditEntry(event.getEventId(), null, EventStatus.RECEIVED,
                    "Event accepted by the ingestion API", "ingest-api"));
            if (event.getStatus() != EventStatus.RECEIVED && event.getStatus() != EventStatus.PROCESSING) {
                audits.save(new AuditEntry(event.getEventId(), EventStatus.RECEIVED, event.getStatus(),
                        event.getMessage(), "crfd-pipeline"));
            }
            seedAlertFor(event, at, random);
            created++;
        }
        seedFeedback(random, now);
        return created;
    }

    // ------------------------------------------------------------------ helpers

    private EventRequest randomRequest(ThreadLocalRandom random) {
        Item item = CATALOGUE.get(random.nextInt(CATALOGUE.size()));
        EventType type = weightedEventType(random);
        Double score = random.nextDouble() < 0.12 ? null : round2(random.nextDouble());
        return new EventRequest(
                RecommendationEvent.newEventId(),
                "COR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "U-" + (1000 + random.nextInt(400)),
                item.id(),
                item.title(),
                SOURCES[random.nextInt(SOURCES.length)],
                SURFACES[random.nextInt(SURFACES.length)],
                type,
                score,
                type == EventType.RATING ? 1 + random.nextInt(5) : null,
                null,
                Instant.now());
    }

    private RecommendationEvent buildHistoricalEvent(ThreadLocalRandom random, Instant at) {
        Item item = CATALOGUE.get(random.nextInt(CATALOGUE.size()));
        EventType type = weightedEventType(random);

        RecommendationEvent event = new RecommendationEvent();
        event.setEventId(RecommendationEvent.newEventId());
        event.setCorrelationId("COR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        event.setUserId("U-" + (1000 + random.nextInt(400)));
        event.setSource(SOURCES[random.nextInt(SOURCES.length)]);
        event.setSurface(SURFACES[random.nextInt(SURFACES.length)]);
        event.setEventType(type);
        event.setClientTimestamp(at);
        event.setCreatedAt(at);

        double roll = random.nextDouble();
        if (roll < 0.03) {
            // structurally invalid: no item attribution -> quarantined
            event.setItemId(null);
            event.setItemTitle(null);
            event.setScore(random.nextDouble() < 0.5 ? round2(random.nextDouble()) : null);
            event.setStatus(EventStatus.QUARANTINED);
            event.setMessage("Rejected: itemId is required to attribute recommendation feedback.");
        } else {
            event.setItemId(item.id());
            event.setItemTitle(item.title());
            Double score = random.nextDouble() < 0.10 ? null : round2(random.nextDouble());
            event.setScore(score);
            if (type == EventType.RATING) {
                event.setRating(1 + random.nextInt(5));
            }
            if (roll < 0.08) {
                event.setStatus(EventStatus.FAILED);
                event.setMessage("Rejected: served impression without a confidence score");
            } else if (roll < 0.12) {
                event.setStatus(EventStatus.PROCESSING);
                event.setMessage("Pipeline picked up the event for enrichment");
            } else if (roll < 0.15) {
                event.setStatus(EventStatus.RECEIVED);
                event.setMessage("Event accepted by the ingestion API");
            } else {
                event.setStatus(EventStatus.PROCESSED);
                event.setMessage("Processed: validators passed and metrics updated.");
            }
        }

        if (event.getStatus() == EventStatus.RECEIVED) {
            event.setLatencyMs(null);
        } else {
            long latency = (long) (35 + random.nextDouble() * 240);
            if (random.nextDouble() < 0.06) {
                latency += 1_100 + (long) (random.nextDouble() * 900);
            }
            event.setLatencyMs(latency);
        }
        if (event.getStatus() == EventStatus.PROCESSED
                || event.getStatus() == EventStatus.FAILED
                || event.getStatus() == EventStatus.QUARANTINED) {
            event.setProcessedAt(at.plusMillis(event.getLatencyMs() == null ? 40 : event.getLatencyMs()));
        }
        return event;
    }

    private void seedAlertFor(RecommendationEvent event, Instant at, ThreadLocalRandom random) {
        AlertCode code = null;
        AlertSeverity severity = AlertSeverity.WARNING;
        String message = null;
        var thresholds = properties.getThresholds();

        if (event.getStatus() == EventStatus.QUARANTINED || event.getStatus() == EventStatus.FAILED) {
            code = AlertCode.SCHEMA_MISMATCH;
            severity = AlertSeverity.CRITICAL;
            message = "Event " + event.getEventId() + " could not be attributed to an item: " + event.getMessage();
        } else if (event.getLatencyMs() != null && event.getLatencyMs() > thresholds.getCriticalLatencyMs()) {
            code = AlertCode.HIGH_LATENCY;
            severity = AlertSeverity.CRITICAL;
            message = "Processing latency %d ms exceeds the critical SLA of %d ms"
                    .formatted(event.getLatencyMs(), thresholds.getCriticalLatencyMs());
        } else if (event.getScore() != null && event.getScore() < thresholds.getLowConfidence()) {
            code = AlertCode.LOW_CONFIDENCE;
            severity = AlertSeverity.WARNING;
            message = "Confidence %.2f for item %s is below the floor of %.2f"
                    .formatted(event.getScore(), event.getItemId(), thresholds.getLowConfidence());
        }
        if (code == null) {
            return;
        }

        Alert alert = new Alert();
        alert.setEventRef(event.getEventId());
        alert.setCode(code);
        alert.setSeverity(severity);
        alert.setMessage(message);
        alert.setCreatedAt(at.plusSeconds(2 + random.nextInt(30)));

        double state = random.nextDouble();
        if (state < 0.55) {
            alert.setStatus(AlertStatus.OPEN);
        } else if (state < 0.78) {
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setOwner("on-call@crfd.example.com");
            alert.setAcknowledgedAt(alert.getCreatedAt().plusSeconds(120));
            alert.setResolutionNote("Investigating the affected recommender slot.");
        } else {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setOwner("relevance-team@crfd.example.com");
            alert.setAcknowledgedAt(alert.getCreatedAt().plusSeconds(90));
            alert.setResolvedAt(alert.getCreatedAt().plusSeconds(600));
            alert.setResolutionNote("Threshold re-tuned and the recommender model re-scored the candidate pool.");
        }
        alertRepository.save(alert);
    }

    private void seedFeedback(ThreadLocalRandom random, Instant now) {
        for (int i = 0; i < 25; i++) {
            Item item = CATALOGUE.get(random.nextInt(CATALOGUE.size()));
            Feedback feedback = new Feedback();
            feedback.setUserId("U-" + (1000 + random.nextInt(400)));
            feedback.setItemId(item.id());
            feedback.setRating(random.nextDouble() < 0.3 ? 1 + random.nextInt(2) : 3 + random.nextInt(3));
            feedback.setReason(feedback.getRating() <= 2 ? REASONS[random.nextInt(REASONS.length)] : null);
            feedback.setComment(feedback.getRating() <= 2 ? "Recommendation did not match my interests." : null);
            feedback.setCreatedAt(now.minusSeconds((long) (random.nextDouble() * 48 * 3600)));
            feedbackRepository.save(feedback);
        }
    }

    private static EventType weightedEventType(ThreadLocalRandom random) {
        double roll = random.nextDouble();
        if (roll < 0.45) {
            return EventType.IMPRESSION;
        } else if (roll < 0.75) {
            return EventType.CLICK;
        } else if (roll < 0.85) {
            return EventType.PURCHASE;
        } else if (roll < 0.95) {
            return EventType.DISMISS;
        }
        return EventType.RATING;
    }

    private static Double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
