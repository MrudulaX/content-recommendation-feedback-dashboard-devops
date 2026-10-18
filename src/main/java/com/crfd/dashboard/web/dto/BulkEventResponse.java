package com.crfd.dashboard.web.dto;

import java.util.List;

public record BulkEventResponse(
        int accepted,
        int rejected,
        List<EventResponse> events,
        List<String> errors
) {
}
