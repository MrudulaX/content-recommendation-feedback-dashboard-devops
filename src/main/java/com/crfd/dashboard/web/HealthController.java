package com.crfd.dashboard.web;

import com.crfd.dashboard.service.HealthService;
import com.crfd.dashboard.web.dto.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoints, split by the question they answer.
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Question</th><th>503 when</th><th>Consumers</th></tr>
 *   <tr>
 *     <td>{@code GET /api/v1/health}</td>
 *     <td>Can this instance serve traffic?</td>
 *     <td>a hard dependency is DOWN</td>
 *     <td>container HEALTHCHECK, Nginx readiness, the Jenkins/Ansible deploy gate</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET /api/v1/health/slo}</td>
 *     <td>Is the pipeline meeting its objective?</td>
 *     <td>the success-rate SLO is breached</td>
 *     <td>monitoring and alerting, the optional quality gate</td>
 *   </tr>
 * </table>
 *
 * <p>The two are kept apart on purpose. A pipeline whose success rate dipped is an
 * operations problem to be alerted on - it is not a reason to take a fully functional
 * node out of service and fail an otherwise good deployment.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Readiness and SLO signals for deployment tooling")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Readiness for load balancers and deployment tooling. Returns {@code 200} for both
     * {@code UP} and {@code DEGRADED}, and {@code 503} only when the instance is
     * {@code DOWN} and genuinely cannot serve requests.
     */
    @GetMapping("/health")
    @Operation(summary = "Deep health check: database, ingestion liveness, alerts and pipeline SLO")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = healthService.check();
        HttpStatus status = "DOWN".equals(response.status())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * The business quality gate. Kept separate from readiness so that monitoring can
     * alert on a breached SLO without causing an outage.
     */
    @GetMapping("/health/slo")
    @Operation(summary = "Pipeline SLO gate: 200 within objective, 503 when the success rate is breached")
    public ResponseEntity<Map<String, Object>> slo() {
        HealthResponse response = healthService.check();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", response.sloBreached() ? "BREACHED" : "WITHIN_OBJECTIVE");
        body.put("sloBreached", response.sloBreached());
        body.put("pipelineSuccessRate", response.checks().get("pipelineSuccessRate"));
        body.put("sloMinimum", response.checks().get("sloMinimum"));
        body.put("criticalOpenAlerts", response.checks().get("criticalOpenAlerts"));
        body.put("checkedAt", response.checkedAt());
        return ResponseEntity.status(response.sloBreached() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .body(body);
    }
}
