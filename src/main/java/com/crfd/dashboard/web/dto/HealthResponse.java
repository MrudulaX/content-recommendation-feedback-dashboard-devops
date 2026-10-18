package com.crfd.dashboard.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Payload of {@code GET /api/v1/health}. Deliberately richer than the actuator probe so
 * that operators and the deployment pipeline get a single, meaningful readiness signal.
 *
 * <p>Two independent questions are answered here, and they are deliberately kept apart:</p>
 * <ul>
 *   <li><b>Can this instance serve traffic?</b> - {@code status} is {@code UP} or
 *       {@code DEGRADED}. Both mean "route requests here"; {@code DOWN} means the
 *       instance has lost a hard dependency and must be taken out of rotation.</li>
 *   <li><b>Is the recommendation pipeline meeting its objective?</b> - {@code sloBreached}
 *       is the business signal. It is reported separately because a slipped success rate
 *       is an operations problem, not a reason to kill a perfectly functional node.</li>
 * </ul>
 *
 * @param status      {@code UP}, {@code DEGRADED} or {@code DOWN}
 * @param sloBreached true when the pipeline success rate is below {@code crfd.thresholds.minimum-success-rate}
 */
public record HealthResponse(
        String status,
        String application,
        String version,
        String environment,
        long uptimeSeconds,
        String database,
        Instant latestEventAt,
        long openAlerts,
        boolean sloBreached,
        Map<String, String> checks,
        Instant checkedAt
) {
}
