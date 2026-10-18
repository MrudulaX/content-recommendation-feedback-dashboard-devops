package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.Feedback;

import java.time.Instant;

public record FeedbackResponse(
        Long id,
        String feedbackRef,
        String eventRef,
        String userId,
        String itemId,
        Integer rating,
        String reason,
        String comment,
        Instant createdAt
) {
    public static FeedbackResponse from(Feedback f) {
        return new FeedbackResponse(f.getId(), f.getFeedbackRef(), f.getEventRef(), f.getUserId(),
                f.getItemId(), f.getRating(), f.getReason(), f.getComment(), f.getCreatedAt());
    }
}
