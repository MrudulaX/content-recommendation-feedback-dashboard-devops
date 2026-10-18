package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.web.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness/health signal used by three consumers at once:
 * the dashboard header pill, the Nginx upstream check, and the Ansible / Jenkins
 * deployment gate ({@code GET /api/v1/health}).
 */
@Service
public class HealthService {

    private final RecommendationEventRepository events;
    private final AlertRepository alerts;
    private final DataSource dataSource;
    private final CrfdProperties properties;

    @Value("${info.app.name:Content Recommendation Feedback Dashboard}")
    private String applicationName;

    @Value("${info.app.version:unknown}")
    private String version;

    private final Instant bootedAt = Instant.now();

    public HealthService(RecommendationEventRepository events,
                         AlertRepository alerts,
                         DataSource dataSource,
                         CrfdProperties properties) {
        this.events = events;
        this.alerts = alerts;
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public HealthResponse check() {
        Map<String, String> checks = new LinkedHashMap<>();

        String database = "UP";
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                database = "DEGRADED";
            }
        } catch (Exception e) {
            database = "DOWN: " + e.getClass().getSimpleName();
        }
        checks.put("database", database);

        Instant latestEventAt = events.findLatestCreatedAt();
        checks.put("ingestion", latestEventAt == null ? "IDLE (no events yet)" : "UP");

        long openAlerts = alerts.countByStatus(AlertStatus.OPEN);
        long criticalOpen = alerts.countByStatusAndSeverity(AlertStatus.OPEN, AlertSeverity.CRITICAL);
        checks.put("openAlerts", String.valueOf(openAlerts));
        checks.put("criticalOpenAlerts", String.valueOf(criticalOpen));

        Instant windowFrom = Instant.now().minus(properties.getMetrics().getWindowHours(), ChronoUnit.HOURS);
        long windowTotal = events.countByCreatedAtAfter(windowFrom);
        long windowFailed = events.countByStatusAndCreatedAtAfter(EventStatus.FAILED, windowFrom)
                + events.countByStatusAndCreatedAtAfter(EventStatus.QUARANTINED, windowFrom);
        double successRate = windowTotal == 0 ? 1d : (double) (windowTotal - windowFailed) / windowTotal;
        checks.put("pipelineSuccessRate", "%.4f".formatted(successRate));
        checks.put("sloMinimum", "%.2f".formatted(properties.getThresholds().getMinimumSuccessRate()));

        boolean databaseUp = "UP".equals(database);
        boolean sloBreached = windowTotal > 0
                && successRate < properties.getThresholds().getMinimumSuccessRate();

        // Readiness and the business SLO are separate concerns:
        //   DOWN     - a hard dependency is gone, stop routing traffic here (503)
        //   DEGRADED - serving correctly, but the pipeline is outside its objective (200)
        //   UP       - serving correctly and within objective (200)
        String status = !databaseUp ? "DOWN" : (sloBreached ? "DEGRADED" : "UP");

        return new HealthResponse(
                status,
                applicationName,
                version,
                properties.getEnvironment(),
                java.time.Duration.between(bootedAt, Instant.now()).toSeconds(),
                database,
                latestEventAt,
                openAlerts,
                sloBreached,
                checks,
                Instant.now());
    }
}
