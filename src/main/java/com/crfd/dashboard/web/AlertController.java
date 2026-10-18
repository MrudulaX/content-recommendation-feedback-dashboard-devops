package com.crfd.dashboard.web;

import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.service.AlertService;
import com.crfd.dashboard.web.dto.AlertDecisionRequest;
import com.crfd.dashboard.web.dto.AlertResponse;
import com.crfd.dashboard.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alert / exception view and its lifecycle actions (CRFD-105). */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Exception view, acknowledge and resolve")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @Operation(summary = "Search alerts by status, severity, code and free text")
    public PageResponse<AlertResponse> search(@RequestParam(required = false) AlertStatus status,
                                             @RequestParam(required = false) AlertSeverity severity,
                                             @RequestParam(required = false) AlertCode code,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(alertService.search(status, severity, code, q, pageable));
    }

    @PostMapping("/{alertRef}/acknowledge")
    @Operation(summary = "Acknowledge an alert (OPEN -> ACKNOWLEDGED)")
    public AlertResponse acknowledge(@PathVariable String alertRef,
                                     @Valid @RequestBody AlertDecisionRequest request) {
        return alertService.acknowledge(alertRef, request);
    }

    @PostMapping("/{alertRef}/resolve")
    @Operation(summary = "Resolve an alert (-> RESOLVED) with an operator note")
    public AlertResponse resolve(@PathVariable String alertRef,
                                 @Valid @RequestBody AlertDecisionRequest request) {
        return alertService.resolve(alertRef, request);
    }
}
