package com.crfd.dashboard.service;

import com.crfd.dashboard.config.CrfdProperties;
import com.crfd.dashboard.domain.AlertStatus;
import com.crfd.dashboard.domain.EventStatus;
import com.crfd.dashboard.domain.EventType;
import com.crfd.dashboard.repository.AlertRepository;
import com.crfd.dashboard.repository.FeedbackRepository;
import com.crfd.dashboard.repository.ItemCountView;
import com.crfd.dashboard.repository.ItemTitleView;
import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.web.dto.SummaryResponse;
import com.crfd.dashboard.web.dto.TimeseriesPoint;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Summary indicators (CRFD-103): the KPI strip, the distributions and the time series
 * that back the dashboard charts.
 */
@Service
public class MetricsService {

    private static final DateTimeFormatter HOUR_LABEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final RecommendationEventRepository events;
    private final AlertRepository alerts;
    private final FeedbackRepository feedback;
    private final CrfdProperties properties;

    public MetricsService(RecommendationEventRepository events,
                          AlertRepository alerts,
                          FeedbackRepository feedback,
                          CrfdProperties properties) {
        this.events = events;
        this.alerts = alerts;
        this.feedback = feedback;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(Integer windowHours) {
        long hours = (windowHours == null || windowHours <= 0)
                ? properties.getMetrics().getWindowHours()
                : windowHours;
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);

        List<Object[]> rows = events.findDimensionRowsSince(from);
        long total = rows.size();

        Map<EventStatus, Long> byStatus = new EnumMap<>(EventStatus.class);
        Map<Object, Long> bySource = new LinkedHashMap<>();
        Map<Object, Long> bySurface = new LinkedHashMap<>();
        Map<EventType, Long> byType = new EnumMap<>(EventType.class);
        for (Object[] row : rows) {
            count(byStatus, (EventStatus) row[0]);
            count(bySource, row[1]);
            count(bySurface, row[2]);
            count(byType, (EventType) row[3]);
        }

        long processed = byStatus.getOrDefault(EventStatus.PROCESSED, 0L);
        long failed = byStatus.getOrDefault(EventStatus.FAILED, 0L);
        long quarantined = byStatus.getOrDefault(EventStatus.QUARANTINED, 0L);
        long impressions = byType.getOrDefault(EventType.IMPRESSION, 0L);
        long clicks = byType.getOrDefault(EventType.CLICK, 0L);
        long purchases = byType.getOrDefault(EventType.PURCHASE, 0L);
        long dismisses = byType.getOrDefault(EventType.DISMISS, 0L);

        Double avgLatency = round(events.averageLatencySince(from));
        Double avgRating = round(feedback.averageRatingSince(from));
        if (avgRating == null) {
            avgRating = round(feedback.averageRating());
        }

        long open = alerts.countByStatus(AlertStatus.OPEN);
        long acknowledged = alerts.countByStatus(AlertStatus.ACKNOWLEDGED);
        long resolved = alerts.countByStatus(AlertStatus.RESOLVED);

        int topN = properties.getMetrics().getTopItems();

        return new SummaryResponse(
                Instant.now(),
                from,
                hours,
                total,
                byStatus.getOrDefault(EventStatus.RECEIVED, 0L),
                byStatus.getOrDefault(EventStatus.PROCESSING, 0L),
                processed,
                failed,
                quarantined,
                ratio(processed, total),
                ratio(failed + quarantined, total),
                impressions,
                clicks,
                purchases,
                dismisses,
                ratio(clicks, impressions),
                ratio(dismisses, impressions),
                avgLatency,
                avgRating,
                open,
                acknowledged,
                resolved,
                alerts.countByStatusAndSeverity(AlertStatus.OPEN,
                        com.crfd.dashboard.domain.AlertSeverity.CRITICAL),
                toMetrics(byStatus),
                toMetrics(bySource),
                toMetrics(bySurface),
                toMetrics(byType),
                topItems(EventType.DISMISS, from, topN),
                topItems(EventType.CLICK, from, topN));
    }

    @Transactional(readOnly = true)
    public List<TimeseriesPoint> timeseries(String granularity, Integer windowHours) {
        boolean daily = "day".equalsIgnoreCase(granularity);
        long defaultHours = daily ? 24 * 7 : properties.getMetrics().getWindowHours();
        long hours = (windowHours == null || windowHours <= 0) ? defaultHours : windowHours;

        Instant now = Instant.now();
        Instant from = truncate(now.minus(hours, ChronoUnit.HOURS), daily);

        Map<Instant, long[]> buckets = new TreeMap<>();
        for (Instant cursor = from; !cursor.isAfter(now); cursor = plus(cursor, daily)) {
            buckets.put(cursor, new long[4]); // total, processed, failed, alerts
        }

        for (Object[] row : events.findTimeAndStatusSince(from)) {
            Instant at = truncate((Instant) row[0], daily);
            long[] bucket = buckets.get(at);
            if (bucket == null) {
                continue;
            }
            bucket[0]++;
            EventStatus status = (EventStatus) row[1];
            if (status == EventStatus.PROCESSED) {
                bucket[1]++;
            } else if (status == EventStatus.FAILED || status == EventStatus.QUARANTINED) {
                bucket[2]++;
            }
        }

        for (Instant at : alerts.findCreatedAtSince(from)) {
            long[] bucket = buckets.get(truncate(at, daily));
            if (bucket != null) {
                bucket[3]++;
            }
        }

        List<TimeseriesPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((bucketStart, counts) -> points.add(new TimeseriesPoint(
                (daily ? DAY_LABEL : HOUR_LABEL).format(bucketStart),
                bucketStart,
                counts[0], counts[1], counts[2], counts[3])));
        return points;
    }

    // ------------------------------------------------------------------ helpers

    private List<SummaryResponse.ItemMetric> topItems(EventType type, Instant from, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ItemCountView> top = events.findTopItemsByEventType(type, from, PageRequest.of(0, limit));
        if (top.isEmpty()) {
            return List.of();
        }
        List<String> ids = top.stream().map(ItemCountView::getItemId).toList();
        Map<String, String> titles = events.findTitlesByItemIds(ids).stream()
                .collect(Collectors.toMap(ItemTitleView::getItemId,
                        v -> v.getItemTitle() == null ? v.getItemId() : v.getItemTitle(),
                        (a, b) -> a));
        return top.stream()
                .map(v -> new SummaryResponse.ItemMetric(
                        v.getItemId(), titles.getOrDefault(v.getItemId(), v.getItemId()), v.getTotal()))
                .toList();
    }

    private static <K> void count(Map<K, Long> target, K key) {
        if (key != null) {
            target.merge(key, 1L, Long::sum);
        }
    }

    private static List<SummaryResponse.Metric> toMetrics(Map<?, Long> source) {
        return source.entrySet().stream()
                .map(e -> new SummaryResponse.Metric(String.valueOf(e.getKey()), e.getValue()))
                .toList();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0d : roundTo4((double) numerator / denominator);
    }

    private static Double round(Double value) {
        return value == null ? null : roundTo4(value);
    }

    private static double roundTo4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static Instant truncate(Instant instant, boolean daily) {
        return daily
                ? instant.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant()
                : instant.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant();
    }

    private static Instant plus(Instant instant, boolean daily) {
        return daily ? instant.plus(1, ChronoUnit.DAYS) : instant.plus(1, ChronoUnit.HOURS);
    }

    /** Exposed for tests / diagnostics. */
    public Function<Instant, String> labeler(boolean daily) {
        return (daily ? DAY_LABEL : HOUR_LABEL)::format;
    }
}
