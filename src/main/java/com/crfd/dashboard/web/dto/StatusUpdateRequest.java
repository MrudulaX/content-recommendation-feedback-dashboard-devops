package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.EventStatus;
import jakarta.validation.constraints.NotNull;

/** Manual status transition requested by an operator from the drill-down view. */
public record StatusUpdateRequest(
        @NotNull(message = "status is required") EventStatus status,
        String note,
        String actor
) {
}
