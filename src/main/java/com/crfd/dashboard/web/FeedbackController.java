package com.crfd.dashboard.web;

import com.crfd.dashboard.service.FeedbackService;
import com.crfd.dashboard.web.dto.FeedbackRequest;
import com.crfd.dashboard.web.dto.FeedbackResponse;
import com.crfd.dashboard.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Explicit recommendation feedback capture (CRFD-106). */
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Feedback", description = "Explicit user feedback on a recommendation")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit explicit feedback (rating 1-5 + coded reason). Ratings <= 2 raise a RATING_DROP alert")
    public FeedbackResponse submit(@Valid @RequestBody FeedbackRequest request) {
        return feedbackService.submit(request);
    }

    @GetMapping
    @Operation(summary = "List explicit feedback, optionally filtered by item")
    public PageResponse<FeedbackResponse> list(@RequestParam(required = false) String itemId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(feedbackService.list(itemId, page, size));
    }
}
