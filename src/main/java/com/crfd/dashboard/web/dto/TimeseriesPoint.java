package com.crfd.dashboard.web.dto;

import java.time.Instant;

/** One bucket of the dashboard time series (hourly or daily). */
public record TimeseriesPoint(
        String bucket,
        Instant from,
        long total,
        long processed,
        long failed,
        long alerts
) {
}
