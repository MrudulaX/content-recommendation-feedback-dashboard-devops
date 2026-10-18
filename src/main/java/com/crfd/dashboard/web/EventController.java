package com.crfd.dashboard.web;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.service.EventService;
import com.crfd.dashboard.web.dto.BulkEventResponse;
import com.crfd.dashboard.web.dto.EventDetailResponse;
import com.crfd.dashboard.web.dto.EventRequest;
import com.crfd.dashboard.web.dto.EventResponse;
import com.crfd.dashboard.web.dto.EventSearchCriteria;
import com.crfd.dashboard.web.dto.PageResponse;
import com.crfd.dashboard.web.dto.StatusUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Data / event entry and the searchable dashboard API.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Recommendation feedback event entry, search and drill-down")
public class EventController {

    private final EventService eventService;
    private final RecommendationEventRepository eventRepository;
    private final CrfdProperties properties;

    public EventController(EventService eventService,
                           RecommendationEventRepository eventRepository,
                           CrfdProperties properties) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ event entry (CRFD-101)

    @PostMapping
    @Operation(summary = "Ingest a single recommendation feedback event")
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        EventResponse created = eventService.ingest(request);
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{eventId}")
                        .buildAndExpand(created.eventId())
                        .toUri())
                .body(created);
    }

    @PostMapping("/bulk")
    @Operation(summary = "Ingest a batch of events (max crfd.simulation.max-batch)")
    public ResponseEntity<BulkEventResponse> createBulk(@Valid @RequestBody List<@Valid EventRequest> requests) {
        if (requests.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (requests.size() > properties.getSimulation().getMaxBatch()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(eventService.ingestBulk(requests));
    }

    // ------------------------------------------------------------------ searchable dashboard (CRFD-102)

    @GetMapping
    @Operation(summary = "Search events with free-text, facet, score and date-range filters")
    public PageResponse<EventResponse> search(@Valid EventSearchCriteria criteria) {
        return PageResponse.of(eventService.search(criteria));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Export the current filtered result set as CSV (capped at 5000 rows)")
    public ResponseEntity<byte[]> export(@Valid EventSearchCriteria criteria) {
        criteria.setSize(200);
        criteria.setPage(0);
        int maxPages = 25; // 25 x 200 = 5000 rows
        StringBuilder csv = new StringBuilder(
                "eventId,correlationId,userId,itemId,itemTitle,source,surface,eventType,score,status,latencyMs,createdAt,message\n");
        for (int page = 0; page < maxPages; page++) {
            criteria.setPage(page);
            org.springframework.data.domain.Page<EventResponse> result = eventService.search(criteria);
            result.getContent().forEach(e -> csv.append(row(e)).append('\n'));
            if (result.isLast()) {
                break;
            }
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"crfd-events.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    // ------------------------------------------------------------------ drill-down (CRFD-104)

    @GetMapping("/{eventId}")
    @Operation(summary = "Drill down into one event: state, timeline and related alerts")
    public EventDetailResponse detail(@PathVariable String eventId) {
        return eventService.detail(eventId);
    }

    @PatchMapping("/{eventId}/status")
    @Operation(summary = "Manually transition an event status (operator action, recorded in the audit trail)")
    public EventResponse updateStatus(@PathVariable String eventId,
                                      @Valid @RequestBody StatusUpdateRequest request) {
        return eventService.updateStatus(eventId, request);
    }

    @PostMapping("/{eventId}/reprocess")
    @Operation(summary = "Requeue a FAILED or QUARANTINED event through the enrichment pipeline")
    public EventResponse reprocess(@PathVariable String eventId) {
        return eventService.reprocess(eventId);
    }

    // ------------------------------------------------------------------ misc

    @GetMapping("/stats/latest")
    @Operation(summary = "Quick liveness probe for the event store")
    public ResponseEntity<String> latest() {
        var page = eventRepository.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt")));
        return page.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(page.getContent().get(0).getEventId());
    }

    private String row(EventResponse e) {
        return List.of(
                        nz(e.eventId()), nz(e.correlationId()), nz(e.userId()), nz(e.itemId()), nz(e.itemTitle()),
                        String.valueOf(e.source()), String.valueOf(e.surface()), String.valueOf(e.eventType()),
                        e.score() == null ? "" : String.valueOf(e.score()),
                        String.valueOf(e.status()),
                        e.latencyMs() == null ? "" : String.valueOf(e.latencyMs()),
                        String.valueOf(e.createdAt()),
                        nz(e.message()))
                .stream()
                .map(EventController::csv)
                .collect(Collectors.joining(","));
    }

    private static String csv(String value) {
        String safe = value.replace("\"", "\"\"");
        return safe.contains(",") || safe.contains("\"") ? "\"" + safe + "\"" : safe;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
