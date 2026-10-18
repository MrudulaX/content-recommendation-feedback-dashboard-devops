package com.crfd.dashboard.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Acknowledge / resolve action on an alert. */
public record AlertDecisionRequest(
        @NotBlank(message = "owner is required") String owner,
        String note
) {
}
