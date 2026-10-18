package com.crfd.dashboard.web.dto;

import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationSource;
import com.crfd.dashboard.domain.Surface;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * Bound from query string parameters for the searchable dashboard (CRFD-102).
 * Every filter is optional and they compose (AND semantics).
 */
public class EventSearchCriteria {

    /** Free-text term matched against eventId, correlationId, userId, itemId, itemTitle, message. */
    private String q;

    private EventStatus status;
    private RecommendationSource source;
    private Surface surface;
    private EventType eventType;
    private String userId;
    private String itemId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant to;

    private Double minScore;
    private Double maxScore;

    /** Only events that have at least one alert attached. */
    private Boolean onlyWithAlerts;

    private int page = 0;
    private int size = 25;
    private String sort = "createdAt,desc";

    public String getQ() { return q; }
    public void setQ(String q) { this.q = q; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public RecommendationSource getSource() { return source; }
    public void setSource(RecommendationSource source) { this.source = source; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public Instant getFrom() { return from; }
    public void setFrom(Instant from) { this.from = from; }

    public Instant getTo() { return to; }
    public void setTo(Instant to) { this.to = to; }

    public Double getMinScore() { return minScore; }
    public void setMinScore(Double minScore) { this.minScore = minScore; }

    public Double getMaxScore() { return maxScore; }
    public void setMaxScore(Double maxScore) { this.maxScore = maxScore; }

    public Boolean getOnlyWithAlerts() { return onlyWithAlerts; }
    public void setOnlyWithAlerts(Boolean onlyWithAlerts) { this.onlyWithAlerts = onlyWithAlerts; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = Math.min(Math.max(1, size), 200); }

    public String getSort() { return sort; }
    public void setSort(String sort) { this.sort = sort; }
}
