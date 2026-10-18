-- =============================================================================
--  Content Recommendation Feedback Dashboard - relational schema (MVP)
--
--  This file is the *reference* DDL. The application builds the same schema at
--  start-up through Hibernate (spring.jpa.hibernate.ddl-auto=update); this script
--  exists so that the design is reviewable on its own, so that DBA review and
--  index tuning have something concrete to look at, and so that a MySQL/H2
--  database can be prepared out of band.
--
--  Target: H2 2.x (file mode) and MySQL 8.x. Dialect notes are inline.
--
--  Design note: the MVP deliberately keeps the event stream in ONE wide table.
--  Writes are the hot path (high ingest rate) and the dashboard is a single
--  index-friendly search over it. Normalising the event payload into a child
--  table, or splitting the stream into an append-only event store, is documented
--  as future work in docs/16-limitations-and-future-enhancements.md.
-- =============================================================================


-- -----------------------------------------------------------------------------
--  recommendation_event : the primary record of the dashboard.
--  One row per recommendation feedback event received from a product surface.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recommendation_event
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,

    -- Business key supplied by the client, not the surrogate id. The unique
    -- constraint is what makes event ingestion idempotent: a producer that
    -- retries its request cannot create a duplicate row.
    event_id         VARCHAR(64)  NOT NULL,

    -- Groups an end-to-end request across surfaces, so one user session can be
    -- reconstructed from the searchable dashboard.
    correlation_id   VARCHAR(64),

    user_id          VARCHAR(64),
    item_id          VARCHAR(64),
    item_title       VARCHAR(256),

    -- Closed value sets, stored as readable strings rather than ordinals so the
    -- data stays interpretable without the application's enum definitions.
    -- Mirrors com.crfd.dashboard.domain.RecommendationSource / Surface / EventType.
    source           VARCHAR(32),                 -- HYBRID | COLLABORATIVE_FILTER | CONTENT_BASED | TRENDING | EDITORIAL
    surface          VARCHAR(32),                 -- HOME_FEED | PRODUCT_PAGE | SEARCH_RESULTS | EMAIL | PUSH_NOTIFICATION
    event_type       VARCHAR(32)  NOT NULL,       -- IMPRESSION | CLICK | ADD_TO_CART | PURCHASE | DISMISS | RATING

    -- The recommendation score the model produced for this item at serve time.
    score            DOUBLE PRECISION,
    rating           INTEGER,                     -- 1..5, only for event_type = RATING

    -- Pipeline state machine: RECEIVED -> PROCESSING -> PROCESSED
    --                                     \-> FAILED  / QUARANTINED
    status           VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    latency_ms       BIGINT,
    message          VARCHAR(512),                -- operator/transition note
    comment          VARCHAR(512),                -- free text captured with the event

    client_timestamp TIMESTAMP(6),                -- when the device says it happened
    created_at       TIMESTAMP(6) NOT NULL,       -- when we accepted it (server clock)
    processed_at     TIMESTAMP(6),                -- when the pipeline finished with it

    CONSTRAINT pk_recommendation_event PRIMARY KEY (id)
);

-- The dashboard searches on these columns, and the summary indicators aggregate
-- over created_at, so each of them is indexed individually. The unique indexes are
-- what make event ingestion idempotent under producer retries.
CREATE UNIQUE INDEX IF NOT EXISTS ix_event_event_id ON recommendation_event (event_id);
CREATE INDEX IF NOT EXISTS ix_event_status       ON recommendation_event (status);
CREATE INDEX IF NOT EXISTS ix_event_created_at   ON recommendation_event (created_at);
CREATE INDEX IF NOT EXISTS ix_event_item         ON recommendation_event (item_id);
CREATE INDEX IF NOT EXISTS ix_event_source       ON recommendation_event (source);


-- -----------------------------------------------------------------------------
--  event_audit : immutable status-transition log. Powers the status drill-down
--  timeline and is the audit trail for every operator action.
--  Append-only: rows are never updated or deleted by the application.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_audit
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    event_ref   VARCHAR(64) NOT NULL,   -- recommendation_event.event_id
    from_status VARCHAR(16),            -- NULL for the initial RECEIVED entry
    to_status   VARCHAR(16) NOT NULL,
    note        VARCHAR(512),
    actor       VARCHAR(64),            -- 'pipeline' | 'system' | an operator id
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_event_audit PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS ix_audit_event_ref ON event_audit (event_ref);


-- -----------------------------------------------------------------------------
--  alert : operational exceptions. Drives the alert / exception view.
--  Raised automatically by the pipeline (low confidence, high latency, stale
--  events, dismiss-rate spikes, rating drops) or manually by an operator.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    alert_ref       VARCHAR(32)  NOT NULL,   -- ALT-XXXXXXXX, the human-facing key
    event_ref       VARCHAR(64),             -- NULL when the alert is not event-scoped
    severity        VARCHAR(16)  NOT NULL DEFAULT 'WARNING',  -- INFO | WARNING | CRITICAL
    code            VARCHAR(32)  NOT NULL,   -- LOW_CONFIDENCE | HIGH_LATENCY | HIGH_DISMISS_RATE |
                                             -- DUPLICATE_EVENT | SCHEMA_MISMATCH | STALE_EVENT | RATING_DROP
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',      -- OPEN | ACKNOWLEDGED | RESOLVED
    message         VARCHAR(512) NOT NULL,
    owner           VARCHAR(64),
    resolution_note VARCHAR(512),
    created_at      TIMESTAMP(6) NOT NULL,
    acknowledged_at TIMESTAMP(6),
    resolved_at     TIMESTAMP(6),
    CONSTRAINT pk_alert PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_alert_ref       ON alert (alert_ref);
CREATE INDEX IF NOT EXISTS ix_alert_status           ON alert (status);
CREATE INDEX IF NOT EXISTS ix_alert_event_ref        ON alert (event_ref);
CREATE INDEX IF NOT EXISTS ix_alert_severity         ON alert (severity);


-- -----------------------------------------------------------------------------
--  recommendation_feedback : explicit, low-volume, high-value user feedback.
--  Kept separate from the behavioural event stream because it arrives at a very
--  different rate and is used differently (quality signal vs. behavioural signal).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recommendation_feedback
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    feedback_ref VARCHAR(40)  NOT NULL,   -- FB-XXXXXXXXXX
    event_ref    VARCHAR(64),             -- optional link back to the rated event
    user_id      VARCHAR(64),
    item_id      VARCHAR(64)  NOT NULL,
    rating       INTEGER      NOT NULL,   -- 1..5, enforced in the service layer
    reason       VARCHAR(48),             -- NOT_RELEVANT | ALREADY_SEEN | POOR_QUALITY | ...
    comment      VARCHAR(512),
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_recommendation_feedback PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_feedback_ref        ON recommendation_feedback (feedback_ref);
CREATE INDEX IF NOT EXISTS ix_feedback_item              ON recommendation_feedback (item_id);
CREATE INDEX IF NOT EXISTS ix_feedback_created_at        ON recommendation_feedback (created_at);


-- =============================================================================
--  Index rationale (short version, for the report)
--
--  ix_event_status        the exception view filters by FAILED / QUARANTINED
--  ix_event_created_at    every summary indicator is a window over created_at
--  ix_event_item          the searchable dashboard's most common filter
--  ix_event_source        source breakdown on the summary panel
--  ix_event_event_id      unique: makes ingestion idempotent under retries
--  ix_audit_event_ref     the drill-down timeline is "all audits for one event"
--  ix_alert_status        the default alert view is "everything still OPEN"
--  ix_alert_severity      the critical-alert count on the health endpoint
--
--  Deliberately NOT indexed: message, comment, item_title. They are only ever
--  read on rows already selected by an indexed predicate, and the MVP search is
--  a substring match rather than a full-text query. See docs/16 for the planned
--  full-text / trigram approach once the table grows.
-- =============================================================================
