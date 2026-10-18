package com.crfd.dashboard.repository;

import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.domain.RecommendationEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for recommendation feedback events.
 *
 * <p>{@link JpaSpecificationExecutor} backs the dynamic, multi-criteria search of the
 * dashboard so that filters compose without a combinatorial explosion of derived queries.</p>
 *
 * <p>Aggregations that return several dimensions at once use tuple projections and are
 * folded in Java ({@code MetricsService}) - portable across H2 and MySQL, and cheap for the
 * MVP window. See {@code docs/16-limitations-and-future-enhancements.md} for the planned
 * move to materialised aggregates.</p>
 */
public interface RecommendationEventRepository
        extends JpaRepository<RecommendationEvent, Long>, JpaSpecificationExecutor<RecommendationEvent> {

    Optional<RecommendationEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    long countByStatus(EventStatus status);

    long countByStatusAndCreatedAtAfter(EventStatus status, Instant from);

    long countByEventTypeAndCreatedAtAfter(EventType eventType, Instant from);

    long countByCreatedAtAfter(Instant from);

    List<RecommendationEvent> findByItemIdAndEventTypeAndCreatedAtAfter(String itemId, EventType eventType, Instant after);

    Optional<RecommendationEvent> findFirstByItemIdOrderByCreatedAtDesc(String itemId);

    /** Average end-to-end processing latency in milliseconds within the window. */
    @Query("select avg(e.latencyMs) from RecommendationEvent e where e.latencyMs is not null and e.createdAt >= :from")
    Double averageLatencySince(@Param("from") Instant from);

    /** Timestamps + status - used to bucket the dashboard time series without loading entities. */
    @Query("select e.createdAt, e.status from RecommendationEvent e where e.createdAt >= :from")
    List<Object[]> findTimeAndStatusSince(@Param("from") Instant from);

    /** Latest event timestamp, used as a liveness/health signal. */
    @Query("select max(e.createdAt) from RecommendationEvent e")
    Instant findLatestCreatedAt();

    /**
     * One row per event carrying the four dimensions the dashboard groups by.
     * Row shape: {@code [EventStatus, RecommendationSource, Surface, EventType]}.
     */
    @Query("select e.status, e.source, e.surface, e.eventType from RecommendationEvent e where e.createdAt >= :from")
    List<Object[]> findDimensionRowsSince(@Param("from") Instant from);

    /** Top items by volume for a given event type (e.g. most dismissed items). */
    @Query("""
            select e.itemId as itemId, count(e) as total
              from RecommendationEvent e
             where e.eventType = :type and e.createdAt >= :from and e.itemId is not null
             group by e.itemId
             order by count(e) desc
            """)
    List<ItemCountView> findTopItemsByEventType(@Param("type") EventType type,
                                                @Param("from") Instant from,
                                                Pageable pageable);

    /** Display titles for a set of item ids (latest title wins). */
    @Query("select e.itemId as itemId, max(e.itemTitle) as itemTitle from RecommendationEvent e where e.itemId in :ids group by e.itemId")
    List<ItemTitleView> findTitlesByItemIds(@Param("ids") List<String> ids);
}
