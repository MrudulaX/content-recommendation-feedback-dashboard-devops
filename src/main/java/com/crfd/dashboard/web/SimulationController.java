package com.crfd.dashboard.web;

import com.crfd.dashboard.service.TrafficSimulator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Synthetic traffic generator - the "Generate traffic" button of the dashboard.
 * Disabled automatically with {@code crfd.simulation.enabled=false} (production profile).
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "crfd.simulation.enabled", havingValue = "true", matchIfMissing = false)
@Tag(name = "Simulation", description = "Synthetic traffic for demos (non-production environments only)")
public class SimulationController {

    private final TrafficSimulator simulator;

    public SimulationController(TrafficSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/simulate")
    @Operation(summary = "Generate N synthetic recommendation feedback events through the real ingestion path")
    public Map<String, Object> simulate(@RequestParam(defaultValue = "25") int count) {
        int created = simulator.simulate(count);
        return Map.of("requested", count, "generated", created);
    }
}
