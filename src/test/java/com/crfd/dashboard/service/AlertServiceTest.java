package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.Alert;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.web.dto.AlertDecisionRequest;
import com.crfd.dashboard.web.dto.AlertResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the alert / exception lifecycle (CRFD-105). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlertService - exception lifecycle")
class AlertServiceTest {

    @Mock
    private AlertRepository alerts;

    private AlertService service;

    @BeforeEach
    void setUp() {
        service = new AlertService(alerts, new CrfdProperties());
        when(alerts.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("raise() creates an OPEN alert")
    void raiseCreatesOpenAlert() {
        when(alerts.existsByEventRefAndCodeAndStatus("EVT-1", AlertCode.HIGH_LATENCY, AlertStatus.OPEN))
                .thenReturn(false);

        AlertResponse response = service.raise("EVT-1", AlertCode.HIGH_LATENCY, AlertSeverity.WARNING, "too slow");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(response.code()).isEqualTo(AlertCode.HIGH_LATENCY);
        assertThat(response.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(response.alertRef()).startsWith("ALT-");
    }

    @Test
    @DisplayName("raise() is idempotent while an identical alert is still OPEN")
    void raiseSuppressesDuplicateOpenAlert() {
        when(alerts.existsByEventRefAndCodeAndStatus("EVT-1", AlertCode.LOW_CONFIDENCE, AlertStatus.OPEN))
                .thenReturn(true);

        assertThat(service.raise("EVT-1", AlertCode.LOW_CONFIDENCE, AlertSeverity.WARNING, "dup")).isNull();
        verify(alerts, never()).save(any(Alert.class));
    }

    @Test
    @DisplayName("acknowledge() moves OPEN -> ACKNOWLEDGED and records the owner")
    void acknowledgeTransitions() {
        Alert alert = open();
        when(alerts.findByAlertRef("ALT-1")).thenReturn(Optional.of(alert));

        AlertResponse response = service.acknowledge("ALT-1",
                new AlertDecisionRequest("on-call@crfd.example.com", "looking into it"));

        assertThat(response.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(response.owner()).isEqualTo("on-call@crfd.example.com");
        assertThat(response.acknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolve() sets RESOLVED, owner and resolution note")
    void resolveTransitions() {
        Alert alert = open();
        when(alerts.findByAlertRef("ALT-1")).thenReturn(Optional.of(alert));

        AlertResponse response = service.resolve("ALT-1",
                new AlertDecisionRequest("relevance-team@crfd.example.com", "model re-scored"));

        assertThat(response.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(response.resolutionNote()).isEqualTo("model re-scored");
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("acknowledging an already resolved alert is rejected")
    void cannotAcknowledgeResolved() {
        Alert alert = open();
        alert.setStatus(AlertStatus.RESOLVED);
        when(alerts.findByAlertRef("ALT-1")).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.acknowledge("ALT-1", new AlertDecisionRequest("ops", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    @DisplayName("unknown alert reference yields NotFoundException")
    void unknownAlert() {
        when(alerts.findByAlertRef("ALT-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("ALT-NOPE", new AlertDecisionRequest("ops", null)))
                .isInstanceOf(NotFoundException.class);
    }

    private static Alert open() {
        Alert alert = new Alert();
        alert.setAlertRef("ALT-1");
        alert.setEventRef("EVT-1");
        alert.setCode(AlertCode.HIGH_LATENCY);
        alert.setSeverity(AlertSeverity.WARNING);
        alert.setStatus(AlertStatus.OPEN);
        alert.setMessage("latency breach");
        return alert;
    }
}
