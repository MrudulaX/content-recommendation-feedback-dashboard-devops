# 05 — API catalogue

Every endpoint the MVP exposes, with real request and response shapes. The live,
machine-readable description is served by the running application at
`/swagger-ui/index.html` and `/v3/api-docs`; this document is the contract in
prose, and it is what the Selenium journeys and the smoke test are written
against.

| | |
| --- | --- |
| Base path | `/api/v1` |
| Content type | `application/json` (except the CSV export) |
| Timestamps | ISO-8601 instants in UTC, e.g. `2026-10-18T12:19:01.952013Z` |
| Errors | One shape for every error — see [§7](#7-the-error-contract) |

---

## 1. Events — `CRFD-101`, `CRFD-102`, `CRFD-104`

### 1.1 `POST /api/v1/events`

Publishes one feedback event. The write path of the whole product.

**Request**

```json
{
  "eventId":       "EVT-7F3A91C4",
  "correlationId": "req-42",
  "userId":        "U-1001",
  "itemId":        "ITM-77",
  "itemTitle":     "Noise-Cancelling Headphones",
  "source":        "COLLABORATIVE_FILTER",
  "surface":       "HOME_FEED",
  "eventType":     "IMPRESSION",
  "score":         0.87,
  "rating":        null,
  "comment":       null,
  "clientTimestamp": "2026-10-18T12:00:00Z"
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `userId` | **yes** | Any stable identifier. |
| `eventType` | **yes** | One of `IMPRESSION`, `CLICK`, `ADD_TO_CART`, `PURCHASE`, `DISMISS`, `RATING`. |
| `eventId` | no | **Omit it and the server mints `EVT-` + 12 uppercase hex.** Supply it and the server treats it as the idempotency key. |
| `itemId` | no | Omitting it is accepted, then quarantined — see below. |
| `score` | no | Must be within `0.0`–`1.0` if supplied. |
| `rating` | no | Must be `1`–`5` if supplied. |
| `source` / `surface` | no | Enumerated; see `docs/04`. |

**Response `201 Created`**

```
Location: /api/v1/events/EVT-7F3A91C4
```

```json
{
  "id": 4211,
  "eventId": "EVT-7F3A91C4",
  "correlationId": "req-42",
  "userId": "U-1001",
  "itemId": "ITM-77",
  "itemTitle": "Noise-Cancelling Headphones",
  "source": "COLLABORATIVE_FILTER",
  "surface": "HOME_FEED",
  "eventType": "IMPRESSION",
  "score": 0.87,
  "rating": null,
  "status": "RECEIVED",
  "latencyMs": null,
  "message": null,
  "comment": null,
  "clientTimestamp": "2026-10-18T12:00:00Z",
  "createdAt": "2026-10-18T12:19:01.952013Z",
  "processedAt": null
}
```

Note the two-stage result. The `201` means *"accepted and queued"*; `status` is
`RECEIVED` and `processedAt` is still null because the pipeline runs after the
response. Poll `GET /api/v1/events/{eventId}` to watch the status settle. This is
deliberate: ingestion must not block on scoring.

**Errors**

| Status | When | Body |
| --- | --- | --- |
| `400` | A field is missing or out of range | `ApiError` with `fieldErrors[]` |
| `409` | `eventId` already exists | `ApiError`, **and a `DUPLICATE_EVENT` alert is raised** |

The `409` doing something *as well as* failing is the point: a producer retry
storm is exactly the kind of thing that should appear in the exception queue
rather than in a log nobody reads.

**Quarantine, not discard.** A payload with no `itemId` is *valid* HTTP but
*unusable* data. It is accepted, marked `QUARANTINED`, and raises a
`SCHEMA_MISMATCH` alert. Rejecting it with `400` would lose the evidence that the
producer is sending broken data.

### 1.2 `POST /api/v1/events/bulk`

A JSON array of the same objects. Each item is validated and processed
independently, so one bad row cannot fail the batch.

**Response `200 OK`**

```json
{
  "accepted": 2,
  "rejected": 1,
  "events":   [ { "eventId": "EVT-AAA111", "status": "RECEIVED", "...": "..." } ],
  "errors":   [ "row 3 (EVT-CCC333): duplicate eventId" ]
}
```

### 1.3 `GET /api/v1/events`

The searchable dashboard's read path. All parameters are optional and compose
with AND semantics.

| Parameter | Type | Default | Notes |
| --- | --- | --- | --- |
| `q` | string | — | Free text, matched against `eventId`, `correlationId`, `userId`, `itemId`, `itemTitle`, `message`. |
| `status` | enum | — | `RECEIVED`, `PROCESSING`, `PROCESSED`, `FAILED`, `QUARANTINED` |
| `source` | enum | — | `COLLABORATIVE_FILTER`, `CONTENT_BASED`, `TRENDING`, `EDITORIAL`, `HYBRID` |
| `surface` | enum | — | `HOME_FEED`, `PRODUCT_PAGE`, `SEARCH_RESULTS`, `EMAIL`, `PUSH_NOTIFICATION` |
| `eventType` | enum | — | `IMPRESSION`, `CLICK`, `ADD_TO_CART`, `PURCHASE`, `DISMISS`, `RATING` |
| `userId`, `itemId` | string | — | Exact match. |
| `from`, `to` | instant | — | Inclusive window over `createdAt`. |
| `minScore`, `maxScore` | double | — | Score range. |
| `onlyWithAlerts` | boolean | — | Only events that raised at least one exception. |
| `page` | int | `0` | Clamped at 0. |
| `size` | int | `25` | **Clamped to 1–200.** |
| `sort` | string | `createdAt,desc` | Any mapped property. |

The `size` clamp is a deliberate safety rail: an unbounded page size turns a
dashboard into a denial-of-service tool.

**Response `200 OK`**

```json
{
  "content": [ { "eventId": "EVT-7F3A91C4", "status": "PROCESSED", "...": "..." } ],
  "page": 0, "size": 25, "totalElements": 180, "totalPages": 8,
  "first": true, "last": false
}
```

### 1.4 `GET /api/v1/events/export`

Same criteria as §1.3, but returns `text/csv` with a
`Content-Disposition: attachment` header. Columns, in order:

```
eventId, correlationId, userId, itemId, itemTitle, source, surface,
eventType, score, status, latencyMs, createdAt, message
```

Capped at **5000 rows**. The cap is reported in the response, not silently
applied, so an analyst cannot mistake a truncated extract for a complete one.

### 1.5 `GET /api/v1/events/{eventId}`

The drill-down. One request returns everything the detail view needs, so the
view cannot show a torn picture assembled from three sequential calls.

**Response `200 OK`**

```json
{
  "event": { "eventId": "EVT-7F3A91C4", "status": "QUARANTINED", "...": "..." },
  "timeline": [
    { "actor": "system",   "fromStatus": null,         "toStatus": "RECEIVED",
      "note": "Event accepted from HOME_FEED",                       "createdAt": "..." },
    { "actor": "pipeline", "fromStatus": "RECEIVED",   "toStatus": "PROCESSING",
      "note": "Enrichment started",                                  "createdAt": "..." },
    { "actor": "pipeline", "fromStatus": "PROCESSING", "toStatus": "QUARANTINED",
      "note": "Missing itemId - cannot resolve the recommended item", "createdAt": "..." }
  ],
  "alerts": [ { "alertRef": "ALT-9C21AB44", "code": "SCHEMA_MISMATCH",
                "severity": "WARNING", "status": "OPEN", "...": "..." } ]
}
```

`timeline` is ordered by `(createdAt, id)` so a repeat request shows the same
order. Without the `id` tiebreak two transitions sharing a millisecond would swap
places between refreshes, which makes an incident timeline untrustworthy exactly
when it matters.

**Errors** — `404` for an unknown `eventId`.

### 1.6 `PATCH /api/v1/events/{eventId}/status`

An operator overrides the status. Always audited; the audit records who, when and
why.

```json
{ "note": "Confirmed with the data team: the score field was never populated",
  "actor": "ops@example.com" }
```

**Response `200 OK`** — the updated `EventResponse`. Note the transition now has
`processedAt` set where applicable, and a further human-actor entry is appended to
the timeline.

**Errors** — `404` unknown event; `409` an illegal transition (for example
re-opening a `PROCESSED` event without reprocessing).

### 1.7 `POST /api/v1/events/{eventId}/reprocess`

Re-runs the pipeline for one event, appending an audit entry. This is the
"try it again now that the upstream is fixed" button, and it exists so that
recovery never means editing the database by hand.

**Response `200 OK`** — the updated `EventResponse`.

### 1.8 `GET /api/v1/events/stats/latest`

A cheap liveness probe for the event store. Returns the newest event's `eventId`
as `text/plain`, or `204 No Content` if the store is empty. Used by the smoke
test and as a quick manual "is anything arriving?" check.

---

## 2. Metrics — `CRFD-103`

### 2.1 `GET /api/v1/metrics/summary`

The KPI panel. `windowHours` overrides the configured window (default 24).

**Response `200 OK`**

```json
{
  "generatedAt": "2026-10-18T12:21:01Z",
  "windowFrom": "2026-10-17T12:21:01Z",
  "windowHours": 24,

  "totalEvents": 180, "receivedEvents": 4, "processingEvents": 0,
  "processedEvents": 154, "failedEvents": 14, "quarantinedEvents": 8,
  "successRate": 0.9348, "failureRate": 0.0652,

  "impressions": 150, "clicks": 12, "purchases": 3, "dismisses": 9,
  "clickThroughRate": 0.08, "dismissRate": 0.06,
  "averageLatencyMs": 268.4, "averageRating": 3.6,

  "openAlerts": 33, "acknowledgedAlerts": 2, "resolvedAlerts": 1,
  "criticalOpenAlerts": 12,

  "byStatus":    [ { "key": "PROCESSED", "count": 154 } ],
  "bySource":    [ { "key": "COLLABORATIVE_FILTER", "count": 42 } ],
  "bySurface":   [ { "key": "HOME_FEED", "count": 61 } ],
  "byEventType": [ { "key": "IMPRESSION", "count": 150 } ],
  "topDismissedItems":   [ { "itemId": "ITM-77", "itemTitle": "…", "count": 4 } ],
  "topRecommendedItems": [ { "itemId": "ITM-12", "itemTitle": "…", "count": 9 } ]
}
```

Every figure is computed by an aggregate SQL query. Nothing is computed by
loading rows into memory and counting them in Java — the difference is invisible
at 180 rows and fatal at 180 million.

### 2.2 `GET /api/v1/metrics/timeseries`

| Parameter | Default | Notes |
| --- | --- | --- |
| `granularity` | `hour` | `hour` or `day`. |
| `windowHours` | configured | Window size. |

**Response `200 OK`**

```json
[
  { "bucket": "2026-10-18T11:00:00Z", "from": "2026-10-18T11:00:00Z",
    "total": 24, "processed": 21, "failed": 2, "alerts": 3 }
]
```

Buckets with no traffic are **present with zeroes**, not omitted. A missing
bucket and a genuinely idle hour look identical on a chart, and only one of them
is worth investigating.

---

## 3. Alerts — `CRFD-105`

### 3.1 `GET /api/v1/alerts`

| Parameter | Default | Notes |
| --- | --- | --- |
| `status` | — | `OPEN`, `ACKNOWLEDGED`, `RESOLVED` |
| `severity` | — | `INFO`, `WARNING`, `CRITICAL` |
| `code` | — | `LOW_CONFIDENCE`, `HIGH_LATENCY`, `HIGH_DISMISS_RATE`, `DUPLICATE_EVENT`, `SCHEMA_MISMATCH`, `STALE_EVENT`, `RATING_DROP` |
| `q` | — | Free text over the alert reference, message and owner. |
| `page`, `size` | `0`, `25` | Paged. |

**Response `200 OK`** — `PageResponse<AlertResponse>`:

```json
{
  "content": [
    { "id": 91, "alertRef": "ALT-9C21AB44", "eventRef": "EVT-7F3A91C4",
      "severity": "WARNING", "code": "SCHEMA_MISMATCH", "status": "OPEN",
      "message": "Event EVT-7F3A91C4 has no itemId; the recommended item cannot be resolved",
      "owner": null, "resolutionNote": null,
      "createdAt": "2026-10-18T12:19:02Z",
      "acknowledgedAt": null, "resolvedAt": null }
  ],
  "page": 0, "size": 25, "totalElements": 33, "totalPages": 2,
  "first": true, "last": false
}
```

### 3.2 `POST /api/v1/alerts/{alertRef}/acknowledge`

```json
{ "note": "Taking this one - investigating the producer for HOME_FEED" }
```

**Response `200 OK`** — the alert with `status: "ACKNOWLEDGED"` and
`acknowledgedAt` set.

**Errors** — `404` unknown reference; `409` the alert is already `RESOLVED`.

That last `409` is a deliberate rule rather than a technicality. Acknowledging a
closed incident would silently reopen it while leaving the resolution note behind,
producing an alert that is neither open nor resolved. Refusing is the honest
behaviour.

### 3.3 `POST /api/v1/alerts/{alertRef}/resolve`

```json
{ "note": "Producer patched; itemId now always sent. Verified over 30 minutes." }
```

**Response `200 OK`** — `status: "RESOLVED"`, `resolvedAt` set.

### 3.4 Duplicate suppression

Raising a second alert with the same `(code, eventRef)` while one is still `OPEN`
is a **no-op**, not a second alert. Repeated breaches fold into the one already in
the queue. This is not an optimisation: a queue that fills with 400 copies of the
same problem is a queue that gets ignored, and an ignored queue is worse than no
queue at all.

---

## 4. Feedback — `CRFD-106`

### 4.1 `POST /api/v1/feedback`

```json
{
  "eventRef": "EVT-7F3A91C4",
  "userId":   "U-1001",
  "itemId":   "ITM-77",
  "rating":   2,
  "reason":   "ALREADY_SEEN",
  "comment":  "Showed me this again after I bought it"
}
```

**Response `201 Created`** — `FeedbackResponse` with a minted `feedbackRef` of the
form `FB-XXXXXXXXXX`.

If `rating` is below the configured floor, a `RATING_DROP` alert is raised in the
same transaction. Errors: `400` for a rating outside `1`–`5` or a missing
`itemId`.

### 4.2 `GET /api/v1/feedback`

| Parameter | Default | Notes |
| --- | --- | --- |
| `itemId` | — | Restrict to one item — the natural way to ask "how is this item liked?" |
| `page`, `size` | `0`, `25` | Paged. |

**Response `200 OK`** — `PageResponse<FeedbackResponse>`.

---

## 5. Health and operations — `FR-7`

### 5.1 `GET /api/v1/health` — readiness

**Consumers:** the container `HEALTHCHECK`, Nginx, the Jenkins deploy gate, the
Ansible `crfd_app` role's health wait, `healthcheck.yml`.

| Instance state | HTTP | Meaning |
| --- | --- | --- |
| `UP` | `200` | Healthy, and meeting the objective |
| `DEGRADED` | `200` | **Serving**, but the pipeline SLO is breached — an alerting matter, not an outage |
| `DOWN` | `503` | A hard dependency is down; cannot serve |

```json
{
  "status": "DEGRADED",
  "application": "Content Recommendation Feedback Dashboard",
  "version": "1.0.0-SNAPSHOT",
  "environment": "docker",
  "uptimeSeconds": 169,
  "database": "UP",
  "latestEventAt": "2026-10-18T12:19:01.952013Z",
  "openAlerts": 33,
  "sloBreached": true,
  "checks": {
    "database": "UP", "ingestion": "UP",
    "openAlerts": "33", "criticalOpenAlerts": "12",
    "pipelineSuccessRate": "0.9027", "sloMinimum": "0.95"
  },
  "checkedAt": "2026-10-18T12:21:01.675147600Z"
}
```

### 5.2 `GET /api/v1/health/slo` — the business gate

**Consumers:** monitoring and alerting; optionally a release-quality gate.

| Pipeline state | HTTP |
| --- | --- |
| within objective | `200` |
| success rate breached | `503` |

```json
{ "status": "BREACHED", "sloBreached": true,
  "pipelineSuccessRate": "0.9027", "sloMinimum": "0.95",
  "criticalOpenAlerts": "12", "checkedAt": "2026-10-18T12:21:01.857754078Z" }
```

**Why these are two endpoints.** If a dip in a business metric could return `503`
from the readiness endpoint, then the container would be marked unhealthy, Nginx
would drop it, and a deployment would fail — turning a reporting concern into an
outage. The two questions have different consumers and different correct actions,
so they are two endpoints. Captured evidence, showing readiness `200 DEGRADED`
next to the SLO gate's `503 BREACHED` from the same running instance, is in
`docs/evidence/health-and-slo.txt`.

### 5.3 `GET /actuator/health`

Spring Boot Actuator, for orchestration liveness. `200` with component detail from
the database, disk space and a ping.

---

## 6. Development support

### 6.1 `POST /api/v1/dev/simulate`

Generates synthetic traffic so the dashboard has something to show, and so the
thresholds and alerts can be exercised on demand.

| Parameter | Default | Notes |
| --- | --- | --- |
| `count` | `25` | How many events to generate. |

```json
{ "generated": 25, "note": "Synthetic traffic for demonstration" }
```

This endpoint, and startup seeding, are **gated by configuration and off in the
production profile**. An endpoint that writes fake data is a security finding if
it ships enabled, which is why the gate exists rather than a comment asking people
not to call it.

---

## 7. The error contract

Every error — validation, not-found, conflict, unexpected — is this shape:

```json
{
  "timestamp": "2026-10-18T12:24:11.221Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for one or more fields",
  "path": "/api/v1/events",
  "fieldErrors": [ { "field": "eventType", "detail": "eventType is required" } ]
}
```

| Status | Raised by | Client action |
| --- | --- | --- |
| `400` | `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` | Fix the request |
| `404` | `NotFoundException`, `NoResourceFoundException` | Wrong URL |
| `405` | `HttpRequestMethodNotSupportedException` | Wrong verb |
| `409` | `DuplicateEventException`, illegal transition or illegal acknowledge | Do not retry as-is |
| `503` | Readiness `DOWN`, or the SLO gate breached | Retry later |
| `500` | Anything genuinely unexpected | Report it, with the correlation id |

`fieldErrors` is present only for `400`. Errors never contain a stack trace; the
stack trace goes to the log, correlated by the request's correlation id, and the
client sees the id. That is the difference between an error a client can act on
and an information leak.

**A defect worth naming.** Early in the build, an unmapped URL and a wrong verb on
a real path both returned `500`, because the catch-all handler treated
`NoResourceFoundException` and `HttpRequestMethodNotSupportedException` as
unexpected faults. A client could not distinguish "you asked wrongly" from "we
broke", and the dashboard's error handling was built on a lie. Both are now
mapped correctly and both have regression tests. Full write-up: `docs/11`.
