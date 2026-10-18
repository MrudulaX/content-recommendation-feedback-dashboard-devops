package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AuditEntry;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationEvent;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.AuditEntryRepository;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.web.dto.EventRequest;
import com.crfd.dashboard.web.dto.EventResponse;
import com.crfd.dashboard.web.dto.StatusUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ingestion rules (CRFD-101) - fast, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventService - ingestion rules")
class EventServiceTest {

    @Mock
    private RecommendationEventRepository events;
    @Mock
    private AuditEntryRepository audits;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private AlertService alertService;
    @Mock
    private PipelineScheduler pipeline;

    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(events, audits, alertRepository, alertService, pipeline, new CrfdProperties());
        when(events.save(any(RecommendationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("accepts a valid event, records the audit entry and triggers the pipeline")
    void acceptsValidEvent() {
        when(events.existsByEventId("EVT-1")).thenReturn(false);

        EventResponse response = service.ingest(request("EVT-1", "ITM-1", 0.81));

        assertThat(response.eventId()).isEqualTo("EVT-1");
        assertThat(response.status()).isEqualTo(EventStatus.RECEIVED);
        assertThat(response.eventType()).isEqualTo(EventType.IMPRESSION);
        verify(audits, times(1)).save(any(AuditEntry.class));
        verify(pipeline, times(1)).schedule("EVT-1");
        verify(alertService, never()).raise(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("rejects a replayed event with 409 semantics and raises a DUPLICATE_EVENT exception")
    void rejectsDuplicate() {
        when(events.existsByEventId("EVT-DUP")).thenReturn(true);

        assertThatThrownBy(() -> service.ingest(request("EVT-DUP", "ITM-1", 0.5)))
                .isInstanceOf(DuplicateEventException.class)
                .hasMessageContaining("EVT-DUP");

        verify(alertService).raise(eq("EVT-DUP"), eq(AlertCode.DUPLICATE_EVENT),
                eq(AlertSeverity.WARNING), anyString());
        verify(pipeline, never()).schedule(anyString());
        verify(events, never()).save(any(RecommendationEvent.class));
    }

    @Test
    @DisplayName("quarantines an event without an itemId instead of dropping it")
    void quarantinesMissingItemId() {
        when(events.existsByEventId("EVT-Q")).thenReturn(false);

        EventResponse response = service.ingest(request("EVT-Q", null, 0.9));

        assertThat(response.status()).isEqualTo(EventStatus.QUARANTINED);
        assertThat(response.message()).contains("itemId is required");
        verify(alertService).raise(eq("EVT-Q"), eq(AlertCode.SCHEMA_MISMATCH),
                eq(AlertSeverity.CRITICAL), anyString());
        verify(pipeline, never()).schedule(anyString());
    }

    @Test
    @DisplayName("flags a late-arriving event as STALE_EVENT but still accepts it")
    void flagsStaleEvent() {
        when(events.existsByEventId("EVT-OLD")).thenReturn(false);
        EventRequest stale = new EventRequest("EVT-OLD", null, "U-1", "ITM-1", "Item",
                RecommendationSource.HYBRID, Surface.HOME_FEED, EventType.CLICK, 0.6, null, null,
                java.time.Instant.now().minus(72, java.time.temporal.ChronoUnit.HOURS));

        EventResponse response = service.ingest(stale);

        assertThat(response.status()).isEqualTo(EventStatus.RECEIVED);
        verify(alertService).raise(eq("EVT-OLD"), eq(AlertCode.STALE_EVENT), eq(AlertSeverity.INFO), anyString());
        verify(pipeline).schedule("EVT-OLD");
    }

    @Test
    @DisplayName("manual status transition is audited with the operator as actor")
    void manualStatusTransitionIsAudited() {
        RecommendationEvent stored = new RecommendationEvent();
        stored.setEventId("EVT-3");
        stored.setEventType(EventType.IMPRESSION);
        stored.setStatus(EventStatus.FAILED);
        when(events.findByEventId("EVT-3")).thenReturn(Optional.of(stored));

        EventResponse response = service.updateStatus("EVT-3",
                new StatusUpdateRequest(EventStatus.PROCESSED,
                        "Re-scored after the model fix", "release-manager"));

        assertThat(response.status()).isEqualTo(EventStatus.PROCESSED);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audits).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("release-manager");
        assertThat(captor.getValue().getFromStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(captor.getValue().getToStatus()).isEqualTo(EventStatus.PROCESSED);
    }

    private static EventRequest request(String eventId, String itemId, Double score) {
        return new EventRequest(eventId, "COR-1", "U-100", itemId, "Sample item",
                RecommendationSource.COLLABORATIVE_FILTER, Surface.HOME_FEED, EventType.IMPRESSION,
                score, null, null, java.time.Instant.now());
    }
}
