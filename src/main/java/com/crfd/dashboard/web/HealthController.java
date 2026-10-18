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

/**
 * Health endpoint used by the container health check, the reverse proxy and the
 * deployment tooling.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Readiness signal for deployment tooling")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** Deep health check: database, ingestion liveness, alerts and pipeline SLO. */
    @GetMapping("/health")
    @Operation(summary = "Deep health check: database, ingestion liveness, alerts and pipeline SLO")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = healthService.check();
        HttpStatus status = "UP".equals(response.status())
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
