package com.crfd.dashboard.web.dto;

import java.util.List;

/** Response for the status drill-down endpoint: event + timeline + related alerts. */
public record EventDetailResponse(
        EventResponse event,
        List<AuditView> timeline,
        List<AlertResponse> alerts
) {
    /** One status transition in the event lifecycle. */
    public record AuditView(
            String actor,
            String fromStatus,
            String toStatus,
            String note,
            java.time.Instant at
    ) {
    }
}
