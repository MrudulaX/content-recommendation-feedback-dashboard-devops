package com.crfd.dashboard.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Explicit user feedback on a recommendation. */
public record FeedbackRequest(
        String eventRef,
        String userId,
        @NotBlank(message = "itemId is required") String itemId,
        @NotNull(message = "rating is required") @Min(1) @Max(5) Integer rating,
        String reason,
        String comment
) {
}
