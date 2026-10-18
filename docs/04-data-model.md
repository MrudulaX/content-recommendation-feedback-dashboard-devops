# 04 — Data model

## 1. Entity-relationship view

```
                    ┌─────────────────────────────────────────┐
                    │          recommendation_event           │
                    │  (the wide, write-hot event stream)      │
                    ├─────────────────────────────────────────┤
                    │ PK  id                  BIGINT           │
                    │ UQ  event_id            VARCHAR(64)      │  ← business key
                    │     correlation_id      VARCHAR(64)      │
                    │     user_id             VARCHAR(64)      │
                    │     item_id             VARCHAR(64)      │
                    │     item_title          VARCHAR(256)     │
                    │     source              VARCHAR(32)      │  enum
                    │     surface             VARCHAR(32)      │  enum
                    │     event_type          VARCHAR(32)      │  enum, NOT NULL
                    │     score               DOUBLE           │
                    │     rating              INTEGER          │
                    │     status              VARCHAR(16)      │  enum, NOT NULL
                    │     latency_ms          BIGINT           │
                    │     message             VARCHAR(512)     │
                    │     comment             VARCHAR(512)     │
                    │     client_timestamp    TIMESTAMP(6)     │
                    │     created_at          TIMESTAMP(6)     │  NOT NULL
                    │     processed_at        TIMESTAMP(6)     │
                    └───────┬─────────────────────┬───────────┘
                            │                     │
             event_ref (logical, not FK)          │ event_ref
                            │                     │
                            ▼                     ▼
        ┌──────────────────────────┐   ┌──────────────────────────────┐
        │       event_audit        │   │            alert             │
        │  append-only timeline    │   │   the exception work queue   │
        ├──────────────────────────┤   ├──────────────────────────────┤
        │ PK  id          BIGINT   │   │ PK  id            BIGINT     │
        │     event_ref   VARCHAR  │   │ UQ  alert_ref     VARCHAR(32)│ ← ALT-XXXXXXXX
        │     from_status VARCHAR  │   │     event_ref     VARCHAR(64)│ ← nullable
        │     to_status   VARCHAR  │   │     severity      VARCHAR(16)│  enum
        │     note        VARCHAR  │   │     code          VARCHAR(32)│  enum
        │     actor       VARCHAR  │   │     status        VARCHAR(16)│  enum
        │     created_at  TIMESTAMP│   │     message       VARCHAR(512)│ NOT NULL
        └──────────────────────────┘   │     owner         VARCHAR(64)│
                                       │     resolution_note VARCHAR  │
                                       │     created_at    TIMESTAMP  │
                                       │     acknowledged_at TIMESTAMP│
                                       │     resolved_at   TIMESTAMP  │
                                       └──────────────────────────────┘
                            │
                            │ event_ref
                            ▼
        ┌──────────────────────────────────────┐
        │        recommendation_feedback       │
        │   explicit, low-volume, high-value   │
        ├──────────────────────────────────────┤
        │ PK  id           BIGINT              │
        │ UQ  feedback_ref VARCHAR(40)         │ ← FB-XXXXXXXXXX
        │     event_ref    VARCHAR(64)         │
        │     user_id      VARCHAR(64)         │
        │     item_id      VARCHAR(64) NOT NULL│
        │     rating       INTEGER     NOT NULL│ 1..5
        │     reason       VARCHAR(48)         │
        │     comment      VARCHAR(512)        │
        │     created_at   TIMESTAMP(6) NOT NULL│
        └──────────────────────────────────────┘
```

## 2. Tables

The runnable DDL is `db/schema.sql`; it mirrors these JPA mappings one-to-one,
including the index names.

### 2.1 `recommendation_event`

The primary record and the reason the product exists. One row per feedback event
received from a product surface.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGINT` | Surrogate primary key, generated. Never exposed to users. |
| `event_id` | `VARCHAR(64)` | **The business key.** Unique. Client-supplied when the producer has one, otherwise minted as `EVT-XXXXXXXXXXXX`. This is what a person searches for and what appears in the drill-down. |
| `correlation_id` | `VARCHAR(64)` | Groups the events of one end-to-end request, so a session can be reconstructed. |
| `user_id`, `item_id` | `VARCHAR(64)` | Who and what. |
| `item_title` | `VARCHAR(256)` | Denormalised deliberately: the dashboard shows a readable title without a join, and the title at *serve time* is what matters historically even if the item is later renamed. |
| `source` | `VARCHAR(32)` | `RecommendationSource`: `COLLABORATIVE_FILTER`, `CONTENT_BASED`, `TRENDING`, `EDITORIAL`, `HYBRID`. |
| `surface` | `VARCHAR(32)` | `Surface`: `HOME_FEED`, `PRODUCT_PAGE`, `SEARCH_RESULTS`, `EMAIL`, `PUSH_NOTIFICATION`. |
| `event_type` | `VARCHAR(32)` | `EventType`: `IMPRESSION`, `CLICK`, `ADD_TO_CART`, `PURCHASE`, `DISMISS`, `RATING`. NOT NULL. |
| `score` | `DOUBLE` | The recommendation score the model produced at serve time. Nullable: a missing score is itself a defect worth quarantining. |
| `rating` | `INTEGER` | 1–5, only meaningful for `RATING` events. |
| `status` | `VARCHAR(16)` | `EventStatus`. NOT NULL, defaults to `RECEIVED`. |
| `latency_ms` | `BIGINT` | Serving latency recorded by the pipeline. Drives the p95 indicator and the latency alerts. |
| `message` | `VARCHAR(512)` | The reason for the current status — operator note or pipeline explanation. Shown in the timeline. |
| `comment` | `VARCHAR(512)` | Free text that arrived with the event. |
| `client_timestamp` | `TIMESTAMP(6)` | When the device says it happened. Trusted no further than display. |
| `created_at` | `TIMESTAMP(6)` | When *we* accepted it. NOT NULL. Every summary window is computed over this column. |
| `processed_at` | `TIMESTAMP(6)` | When the pipeline finished with it. Null means never finished. |

### 2.2 `event_audit`

The status-transition log. **Append-only**: the application never updates or
deletes a row here.

| Column | Notes |
| --- | --- |
| `event_ref` | The event's business key. |
| `from_status` | Null for the initial `RECEIVED` entry. |
| `to_status` | Where it moved to. |
| `note` | Human-readable reason for the transition. |
| `actor` | `pipeline`, `system`, or an operator identifier. This is what makes an operator's action attributable. |
| `created_at` | When the transition happened. |

The timeline is ordered by `(created_at, id)`, not by `created_at` alone. Two
transitions can share a timestamp at this resolution, and the order shown to a
human must be stable across requests. This is a deliberate, tested ordering choice.

### 2.3 `alert`

An operational exception. Drives the exception work queue.

| Column | Notes |
| --- | --- |
| `alert_ref` | Unique human-readable key, `ALT-XXXXXXXX`. |
| `event_ref` | The event that caused it. Nullable, because not every exception is scoped to one event (a dismiss-rate spike, for example, is a window statistic). |
| `severity` | `INFO`, `WARNING`, `CRITICAL`. |
| `code` | `LOW_CONFIDENCE`, `HIGH_LATENCY`, `HIGH_DISMISS_RATE`, `DUPLICATE_EVENT`, `SCHEMA_MISMATCH`, `STALE_EVENT`, `RATING_DROP`. |
| `status` | `OPEN`, `ACKNOWLEDGED`, `RESOLVED`. |
| `message` | What went wrong, in words an operator can act on. NOT NULL. |
| `owner` | Who claimed it. Free text in the MVP (see `docs/16`). |
| `resolution_note` | Why it was closed. |
| `created_at`, `acknowledged_at`, `resolved_at` | The lifecycle timestamps. |

**Suppression rule.** Raising a second `OPEN` alert with the same `(code,
event_ref)` is a no-op. An exception queue that fills with duplicates is an
exception queue nobody reads, and an ignored queue is worse than no queue at all.
The second occurrence increments nothing — it is simply not a new problem.

### 2.4 `recommendation_feedback`

Explicit, low-volume, high-value user feedback. Kept separate from the event
stream because it arrives at a completely different rate (tens per day, not
millions) and is used differently: the event stream is a behavioural signal, this
is a stated one.

| Column | Notes |
| --- | --- |
| `feedback_ref` | Unique key, `FB-XXXXXXXXXX`. |
| `event_ref` | Optional link to the interaction being rated. |
| `rating` | 1–5, NOT NULL. Enforced in the service layer, which rejects anything else with `400`. |
| `reason` | `NOT_RELEVANT`, `ALREADY_SEEN`, `POOR_QUALITY`, … A closed-ish vocabulary, stored as text. |

## 3. Event status life cycle

```
            ┌──────────┐
   ingest ─▶│ RECEIVED │
            └────┬─────┘
                 │ pipeline picks it up
                 ▼
            ┌────────────┐
            │ PROCESSING │
            └────┬───────┘
      ┌──────────┼──────────┐
      │          │          │
      ▼          ▼          ▼
┌───────────┐ ┌────────┐ ┌─────────────┐
│ PROCESSED │ │ FAILED │ │ QUARANTINED │
└───────────┘ └────────┘ └─────────────┘
   happy     scoring    payload cannot
   path      failed     be interpreted
```

- `FAILED` — the pipeline tried and could not finish (for example a scoring
  timeout). Counted against the success rate.
- `QUARANTINED` — the payload was structurally unacceptable (for example a
  `HYBRID` recommendation with no score). Kept rather than dropped, because the
  producer sending it is a bug worth fixing.

Both terminal states raise alerts, and both are visible in the exception view.
An operator can override the status manually from the drill-down, and can trigger
a reprocess; both actions are audited.

## 4. Indexing

| Index | Table | Rationale |
| --- | --- | --- |
| `ix_event_event_id` | `recommendation_event` | **Unique.** Makes ingestion idempotent under producer retries — the database, not the application, is the final arbiter. |
| `ix_event_created_at` | `recommendation_event` | Every summary indicator is a window over `created_at`, and the default sort is newest-first. |
| `ix_event_status` | `recommendation_event` | The exception filtering path selects `FAILED` / `QUARANTINED`. |
| `ix_event_item` | `recommendation_event` | The dashboard's most common facet. |
| `ix_event_source` | `recommendation_event` | The per-source breakdown on the summary panel. |
| `ix_audit_event_ref` | `event_audit` | The drill-down timeline is "all audits for one event". |
| `ix_alert_ref` | `alert` | **Unique.** The human key must be both unique and lookup-able. |
| `ix_alert_status` | `alert` | The default exception view is "everything still `OPEN`". |
| `ix_alert_severity` | `alert` | The critical count on the health endpoint. |
| `ix_alert_event_ref` | `alert` | Related exceptions on the drill-down. |
| `ix_feedback_ref` | `recommendation_feedback` | **Unique.** Business key. |
| `ix_feedback_item` | `recommendation_feedback` | Feedback is looked at per item. |
| `ix_feedback_created_at` | `recommendation_feedback` | Feedback trends. |

**Deliberately not indexed:** `message`, `comment`, `item_title`. They are only
read on rows already selected by an indexed predicate, and the MVP search over
them is a substring match rather than a full-text query. Indexing them would add
write cost to the hottest path for no read benefit at this volume. When the table
outgrows substring matching, the answer is a trigram or full-text index, not an
index on these three columns (see `docs/16`).

## 5. Deliberate design choices

**No foreign keys.** `event_audit.event_ref` and the various `*_ref` columns are
logical references to a business key, not physical foreign keys. Three reasons:

1. The event stream is append-hot; a foreign key adds a lock and a check to every
   insert on the hottest path.
2. An audit entry must survive the event it describes being archived or purged.
   A cascading delete would destroy the audit trail, which is the opposite of what
   an audit trail is for.
3. The MVP is single-database. When the read model is split out (see `docs/16`),
   physical keys would have to be removed anyway.

**Enum values stored as strings, not ordinals.** The data stays interpretable
without the application's enum definitions. An ordinal is a decode table that
lives only in compiled code, and the cost of a slightly wider column is trivial
against the cost of being unable to read the table.

**Timestamps are `Instant`, stored as UTC.** One machine, one timeline. Only the
browser localises, and it does so for display only.

**`item_title` is denormalised on purpose.** The title *at serve time* is the
historically interesting value; joining to a current item catalogue would
silently rewrite history every time an item is renamed.

## 6. Retention and volume

| Concern | MVP position | Future |
| --- | --- | --- |
| Growth | ~180 events seeded, plus simulated traffic; H2 file mode | The table is the write-hot path; partitioning by month on `created_at` is the natural first step |
| Purging | None — nothing is deleted, which keeps the drill-down trustworthy | A retention job that archives to object storage, with `event_audit` retained |
| Backup | The `crfd-data` volume, plus `db/schema.sql` to recreate structure | Scheduled dump plus point-in-time recovery once MySQL is the system of record |
| Export | CSV of the current filter, capped at 5000 rows | Warehouse extract, uncapped, off the request path |
