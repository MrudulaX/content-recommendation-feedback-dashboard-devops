package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.Feedback;
import com.crfd.dashboard.repository.FeedbackRepository;
import com.crfd.dashboard.web.dto.FeedbackRequest;
import com.crfd.dashboard.web.dto.FeedbackResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit user feedback on recommendations (CRFD-106). Low ratings become
 * {@code RATING_DROP} exceptions so that a relevance regression is visible on the
 * dashboard before it shows up in the click-through rate.
 */
@Service
public class FeedbackService {

    private final FeedbackRepository feedback;
    private final AlertService alertService;
    private final CrfdProperties properties;

    public FeedbackService(FeedbackRepository feedback, AlertService alertService, CrfdProperties properties) {
        this.feedback = feedback;
        this.alertService = alertService;
        this.properties = properties;
    }

    @Transactional
    public FeedbackResponse submit(FeedbackRequest request) {
        Feedback entity = new Feedback();
        entity.setEventRef(request.eventRef());
        entity.setUserId(request.userId());
        entity.setItemId(request.itemId());
        entity.setRating(request.rating());
        entity.setReason(request.reason());
        entity.setComment(request.comment());
        Feedback saved = feedback.save(entity);

        if (saved.getRating() <= properties.getThresholds().getLowRatingThreshold()) {
            alertService.raise(saved.getFeedbackRef(), AlertCode.RATING_DROP, AlertSeverity.WARNING,
                    "Explicit feedback %s: item %s rated %d/5%s".formatted(
                            saved.getFeedbackRef(), saved.getItemId(), saved.getRating(),
                            saved.getReason() == null ? "" : " (" + saved.getReason() + ")"));
        }
        return FeedbackResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<FeedbackResponse> list(String itemId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<Feedback> result = (itemId == null || itemId.isBlank())
                ? feedback.findAllByOrderByCreatedAtDesc(pageable)
                : feedback.findByItemIdOrderByCreatedAtDesc(itemId, pageable);
        return result.map(FeedbackResponse::from);
    }

    @Transactional(readOnly = true)
    public Double averageRatingForItem(String itemId) {
        return feedback.averageRatingForItem(itemId);
    }

    @Transactional(readOnly = true)
    public Double averageRating() {
        return feedback.averageRating();
    }
}
