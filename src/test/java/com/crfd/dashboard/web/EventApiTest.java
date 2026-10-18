package com.crfd.dashboard.web;

import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.AuditEntryRepository;
import com.crfd.dashboard.repository.FeedbackRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API tests over the real Spring context and the real H2 database
 * (synchronous pipeline) - one test per MVP capability:
 *
 * <ul>
 *   <li>CRFD-101 data / event entry</li>
 *   <li>CRFD-102 searchable dashboard</li>
 *   <li>CRFD-103 summary indicators</li>
 *   <li>CRFD-104 status drill-down</li>
 *   <li>CRFD-105 alert / exception view</li>
 *   <li>CRFD-106 explicit feedback</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CRFD MVP API")
class EventApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecommendationEventRepository events;

    @Autowired
    private AlertRepository alerts;

    @Autowired
    private AuditEntryRepository audits;

    @Autowired
    private FeedbackRepository feedback;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        audits.deleteAll();
        feedback.deleteAll();
        events.deleteAll();
    }

    // ---------------------------------------------------------------- CRFD-101

    @Test
    @DisplayName("CRFD-101: POST /api/v1/events accepts an event, processes it and returns 201 + Location")
    void ingestEvent() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("EVT-A1", "U-900", "ITM-2001", "IMPRESSION", 0.87, null)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.eventId").value("EVT-A1"));

        // The pipeline runs after the ingest transaction commits - assert on the stored state.
        mockMvc.perform(get("/api/v1/events/EVT-A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("PROCESSED"))
                .andExpect(jsonPath("$.event.latencyMs").isNumber())
                .andExpect(jsonPath("$.timeline", hasSize(3)));

        assertThat(events.findByEventId("EVT-A1")).isPresent();
    }

    @Test
    @DisplayName("CRFD-101: a replayed eventId is rejected with 409 and raises a DUPLICATE_EVENT alert")
    void rejectDuplicateEvent() throws Exception {
        String body = eventJson("EVT-DUP", "U-900", "ITM-2001", "CLICK", 0.7, null);

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EVT-DUP")));

        assertThat(alerts.findAll())
                .anyMatch(a -> "EVT-DUP".equals(a.getEventRef()) && a.getCode().name().equals("DUPLICATE_EVENT"));
    }

    @Test
    @DisplayName("CRFD-101: an event without itemId is QUARANTINED and raises a SCHEMA_MISMATCH alert")
    void quarantineEventWithoutItemId() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", "EVT-Q1");
        payload.put("userId", "U-901");
        payload.put("eventType", "IMPRESSION");
        payload.put("score", 0.55);

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        assertThat(alerts.findAll())
                .anyMatch(a -> "EVT-Q1".equals(a.getEventRef()) && a.getCode().name().equals("SCHEMA_MISMATCH"));
    }

    @Test
    @DisplayName("CRFD-101: bean validation rejects a payload without userId with a field error")
    void rejectInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"CLICK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("CRFD-101: bulk ingestion reports accepted and rejected counts")
    void bulkIngest() throws Exception {
        String payload = "[" + eventJson("EVT-B1", "U-1", "ITM-3001", "CLICK", 0.6, null) + ","
                + eventJson("EVT-B2", "U-2", "ITM-3002", "PURCHASE", 0.7, null) + "]";

        mockMvc.perform(post("/api/v1/events/bulk").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    // ---------------------------------------------------------------- CRFD-102

    @Test
    @DisplayName("CRFD-102: search filters by free text, status and event type and pages the result")
    void searchDashboard() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                    .content(eventJson("EVT-S" + i, "U-77", "ITM-9" + i, "CLICK", 0.8, null)));
        }
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-S9", "U-88", "ITM-999", "DISMISS", 0.8, null)));

        mockMvc.perform(get("/api/v1/events").param("q", "U-77"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.size").value(25));

        mockMvc.perform(get("/api/v1/events").param("eventType", "DISMISS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/events").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("CRFD-102: CSV export returns the filtered rows as an attachment")
    void csvExport() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-CSV", "U-CSV", "ITM-CSV", "CLICK", 0.9, null)));

        mockMvc.perform(get("/api/v1/events/export").param("q", "EVT-CSV"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("crfd-events.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EVT-CSV")));
    }

    // ---------------------------------------------------------------- CRFD-103

    @Test
    @DisplayName("CRFD-103: summary indicators aggregate volume, rates, latency and alert counts")
    void summaryIndicators() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-M1", "U-1", "ITM-1", "IMPRESSION", 0.9, null)));
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-M2", "U-2", "ITM-2", "CLICK", 0.9, null)));
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-M3", "U-3", "ITM-3", "DISMISS", 0.05, null)));

        mockMvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(3))
                .andExpect(jsonPath("$.processedEvents").value(3))
                .andExpect(jsonPath("$.impressions").value(1))
                .andExpect(jsonPath("$.clicks").value(1))
                .andExpect(jsonPath("$.clickThroughRate").value(1.0))
                .andExpect(jsonPath("$.averageLatencyMs").isNumber())
                .andExpect(jsonPath("$.byStatus", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("CRFD-103: timeseries returns one bucket per hour with alerts overlaid")
    void timeseries() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-T1", "U-1", "ITM-1", "IMPRESSION", 0.02, null)));

        mockMvc.perform(get("/api/v1/metrics/timeseries").param("granularity", "hour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].bucket").exists())
                .andExpect(jsonPath("$[0].total").isNumber());
    }

    // ---------------------------------------------------------------- CRFD-104

    @Test
    @DisplayName("CRFD-104: drill-down returns the event, its audited timeline and related alerts")
    void drillDown() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-D1", "U-42", "ITM-42", "IMPRESSION", 0.03, null)));

        mockMvc.perform(get("/api/v1/events/EVT-D1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.eventId").value("EVT-D1"))
                .andExpect(jsonPath("$.timeline", hasSize(greaterThanOrEqualTo(3))))
                .andExpect(jsonPath("$.timeline[0].toStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.timeline[1].toStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.timeline[2].toStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.alerts", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.alerts[0].code").value("LOW_CONFIDENCE"));
    }

    @Test
    @DisplayName("CRFD-104: an operator can force a status transition and it is appended to the timeline")
    void manualStatusTransition() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-D2", "U-43", "ITM-43", "CLICK", 0.8, null)));

        mockMvc.perform(patch("/api/v1/events/EVT-D2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"note\":\"investigating\",\"actor\":\"sre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(get("/api/v1/events/EVT-D2"))
                .andExpect(jsonPath("$.event.status").value("FAILED"))
                .andExpect(jsonPath("$.timeline", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @DisplayName("Regression CRFD-121: an unmapped URL is a 404, not a 500")
    void unmappedUrlReturnsNotFoundInsteadOfServerError() throws Exception {
        // Spring throws NoResourceFoundException for a path that matches no handler and
        // no static resource. Before the fix the catch-all handler turned that into a 500,
        // which told clients to retry a request that could never succeed.
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No endpoint matches")));
    }

    @Test
    @DisplayName("Regression CRFD-121: the wrong verb on a real path is a 405, not a 500")
    void wrongVerbReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("EVT-V1", "U-V", "ITM-V", "CLICK", 0.8, null)))
                .andExpect(status().isCreated());

        // /events/{eventId}/status exists, but only for PATCH: a GET must be rejected
        // with 405, and must say which verbs are allowed.
        mockMvc.perform(get("/api/v1/events/EVT-V1/status"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not supported")));

        mockMvc.perform(delete("/api/v1/events/EVT-V1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("CRFD-104: an unknown eventId returns 404 with the uniform error body")
    void unknownEventReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/events/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/events/NOPE"));
    }

    // ---------------------------------------------------------------- CRFD-105

    @Test
    @DisplayName("CRFD-105: alerts can be filtered, acknowledged and resolved")
    void alertLifecycle() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-A9", "U-9", "ITM-9", "IMPRESSION", 0.01, null)));

        String body = mockMvc.perform(get("/api/v1/alerts").param("status", "OPEN").param("code", "LOW_CONFIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getContentAsString();

        String alertRef = objectMapper.readTree(body).path("content").get(0).path("alertRef").asText();

        mockMvc.perform(post("/api/v1/alerts/" + alertRef + "/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"on-call@crfd.example.com\",\"note\":\"triage\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        mockMvc.perform(post("/api/v1/alerts/" + alertRef + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"on-call@crfd.example.com\",\"note\":\"model re-scored\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").exists());

        assertThat(alerts.findByAlertRef(alertRef).orElseThrow().getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }

    // ---------------------------------------------------------------- CRFD-106

    @Test
    @DisplayName("CRFD-106: explicit feedback is stored and a low rating raises RATING_DROP")
    void explicitFeedback() throws Exception {
        mockMvc.perform(post("/api/v1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"ITM-5001\",\"userId\":\"U-1\",\"rating\":1,\"reason\":\"NOT_RELEVANT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackRef").exists());

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"ITM-5001\",\"userId\":\"U-2\",\"rating\":5}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/feedback").param("itemId", "ITM-5001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        assertThat(alerts.findAll())
                .anyMatch(a -> a.getCode().name().equals("RATING_DROP"));

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"ITM-5001\",\"rating\":9}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- deployment signal

    @Test
    @DisplayName("Deployment gate: GET /api/v1/health returns 200 with a database check")
    void deepHealth() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-H1", "U-1", "ITM-1", "CLICK", 0.9, null)));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.checks.database").value("UP"))
                .andExpect(jsonPath("$.latestEventAt").exists());
    }

    @Test
    @DisplayName("Readiness stays 200 (DEGRADED) when the pipeline SLO slips, and /health/slo reports the breach")
    void readinessIsIndependentOfTheSloGate() throws Exception {
        // Ten events, six of which fail: a 0.40 success rate, below the test profile's
        // 0.50 objective (and far below the 0.95 production default).
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                    .content(eventJson("EVT-SLO-" + i, "U-SLO", "ITM-SLO", "IMPRESSION", 0.7, null)));
        }
        for (int i = 1; i <= 6; i++) {
            mockMvc.perform(patch("/api/v1/events/EVT-SLO-" + i + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"FAILED\",\"note\":\"slo probe\",\"actor\":\"test\"}"))
                    .andExpect(status().isOk());
        }

        // A slipped objective is an operations problem, NOT a reason to take the node
        // out of rotation: readiness must stay 200 so a deployment can still proceed.
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.sloBreached").value(true));

        // ... while the dedicated SLO gate fails, for monitoring to alert on.
        mockMvc.perform(get("/api/v1/health/slo"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("BREACHED"))
                .andExpect(jsonPath("$.sloBreached").value(true))
                .andExpect(jsonPath("$.pipelineSuccessRate").exists());
    }

    @Test
    @DisplayName("The SLO gate passes when the pipeline is within objective")
    void sloGatePassesWhenWithinObjective() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("EVT-SLO-OK", "U-OK", "ITM-OK", "CLICK", 0.9, null)));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sloBreached").value(false));

        mockMvc.perform(get("/api/v1/health/slo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sloBreached").value(false));
    }

    @Test
    @DisplayName("Regression CRFD-117: no event type can produce an HTTP 500")
    void noEventTypeProducesServerError() throws Exception {
        for (String type : new String[]{"IMPRESSION", "CLICK", "ADD_TO_CART", "PURCHASE", "DISMISS", "RATING"}) {
            mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson("EVT-R-" + type, "U-REG", "ITM-REG", type, 0.5, null)))
                    .andExpect(status().isCreated());
        }
        assertThat(events.findAll()).hasSize(6);
    }

    // ---------------------------------------------------------------- helpers

    private String eventJson(String eventId, String userId, String itemId, String type, Double score, Integer rating)
            throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", eventId);
        node.put("correlationId", "COR-" + eventId);
        node.put("userId", userId);
        node.put("itemId", itemId);
        node.put("itemTitle", "Item " + itemId);
        node.put("source", "HYBRID");
        node.put("surface", "HOME_FEED");
        node.put("eventType", type);
        if (score != null) {
            node.put("score", score);
        }
        if (rating != null) {
            node.put("rating", rating);
        }
        return objectMapper.writeValueAsString(node);
    }
}
