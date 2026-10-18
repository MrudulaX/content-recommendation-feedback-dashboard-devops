package com.crfd.dashboard.web;

import com.crfd.dashboard.service.MetricsService;
import com.crfd.dashboard.web.dto.SummaryResponse;
import com.crfd.dashboard.web.dto.TimeseriesPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Summary indicators for the dashboard KPI strip and charts (CRFD-103). */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Summary indicators and time series")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/summary")
    @Operation(summary = "KPI strip: volumes, rates, latency, ratings, alert counts and distributions")
    public SummaryResponse summary(@RequestParam(required = false) Integer windowHours) {
        return metricsService.summary(windowHours);
    }

    @GetMapping("/timeseries")
    @Operation(summary = "Bucketed event/alert time series (granularity=hour|day)")
    public List<TimeseriesPoint> timeseries(@RequestParam(required = false, defaultValue = "hour") String granularity,
                                            @RequestParam(required = false) Integer windowHours) {
        return metricsService.timeseries(granularity, windowHours);
    }
}
