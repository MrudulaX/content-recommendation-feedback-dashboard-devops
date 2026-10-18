-- =============================================================================
--  Content Recommendation Feedback Dashboard - seed / demo data
--
--  Small, deterministic data set used for:
--    * demonstrating the dashboard without waiting for live traffic
--    * manual exploratory testing
--    * the "clean node" demonstration, where a freshly provisioned node should
--      immediately show something meaningful
--
--  The application's own DataSeeder produces a much larger, statistically
--  interesting backdated set (180 events over 48 hours) when
--  crfd.simulation.seed-on-startup=true. This script is the small, reviewable
--  version: it is what a reviewer reads to understand the shape of the data.
--
--  Idempotent by construction: each table's *seed rows only* are cleared first
--  (matched on the SEED marker in the business key), then re-inserted. Running
--  it twice therefore leaves the same rows, and it will never delete data that
--  was produced by real traffic or by the application's own seeder.
-- =============================================================================

-- Clear only our own rows. Children first, so this stays correct if foreign keys
-- are added later.
DELETE FROM recommendation_feedback WHERE feedback_ref LIKE 'FB-SEED-%';
DELETE FROM alert                   WHERE alert_ref    LIKE 'ALT-SEED-%';
DELETE FROM event_audit             WHERE event_ref    LIKE 'EVT-SEED-%';
DELETE FROM recommendation_event    WHERE event_id     LIKE 'EVT-SEED-%';


-- ---------------------------------------------------------------------------
--  Events: one of each meaningful outcome, so every filter and every summary
--  indicator has something to find.
-- ---------------------------------------------------------------------------
INSERT INTO recommendation_event (event_id, correlation_id, user_id, item_id, item_title,
                                  source, surface, event_type, score, rating, status,
                                  latency_ms, message, created_at, processed_at)
VALUES
-- A clean, fast, high-confidence recommendation that the user clicked.
('EVT-SEED-0001', 'COR-SEED-0001', 'U-1001', 'ITM-2001', 'Deep Learning for Practitioners',
 'HYBRID', 'HOME_FEED', 'CLICK', 0.94, NULL, 'PROCESSED', 41, 'high confidence, clicked',
 TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP)),

-- A low-confidence recommendation: raises LOW_CONFIDENCE and feeds the alert view.
('EVT-SEED-0002', 'COR-SEED-0002', 'U-1002', 'ITM-2002', 'Budget Air Fryer, 4L',
 'COLLABORATIVE_FILTER', 'HOME_FEED', 'IMPRESSION', 0.12, NULL, 'PROCESSED', 55, 'low confidence: 0.12',
 TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP)),

-- A slow serving path: raises HIGH_LATENCY.
('EVT-SEED-0003', 'COR-SEED-0003', 'U-1003', 'ITM-2003', 'Standing Desk Converter',
 'CONTENT_BASED', 'SEARCH_RESULTS', 'IMPRESSION', 0.61, NULL, 'PROCESSED', 1720, 'serving latency 1720ms',
 TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP)),

-- A pipeline failure: lands in the exception view and drags the success rate down.
('EVT-SEED-0004', 'COR-SEED-0004', 'U-1004', 'ITM-2004', 'Noise-Cancelling Headphones',
 'TRENDING', 'PRODUCT_PAGE', 'IMPRESSION', 0.77, NULL, 'FAILED', 2500, 'model timeout while scoring',
 TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP)),

-- A quarantined event: rejected payload, kept for investigation.
('EVT-SEED-0005', 'COR-SEED-0005', 'U-1005', 'ITM-2005', 'Espresso Machine, Compact',
 'HYBRID', 'EMAIL', 'IMPRESSION', NULL, NULL, 'QUARANTINED', 88, 'missing score for HYBRID source',
 TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP)),

-- A dismiss from a push surface: contributes to the dismiss-rate indicator.
('EVT-SEED-0006', 'COR-SEED-0006', 'U-1006', 'ITM-2006', 'Yoga Mat, Extra Thick',
 'TRENDING', 'PUSH_NOTIFICATION', 'DISMISS', 0.45, NULL, 'PROCESSED', 37, 'dismissed from push',
 TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP)),

-- A purchase: the positive end of the funnel, for the click-through/funnel panel.
('EVT-SEED-0007', 'COR-SEED-0007', 'U-1007', 'ITM-2007', 'Mechanical Keyboard, TKL',
 'HYBRID', 'PRODUCT_PAGE', 'PURCHASE', 0.89, NULL, 'PROCESSED', 44, 'converted',
 TIMESTAMPADD(HOUR, -16, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -16, CURRENT_TIMESTAMP)),

-- A low star rating: raises RATING_DROP.
('EVT-SEED-0008', 'COR-SEED-0008', 'U-1008', 'ITM-2008', 'Wireless Mouse, Ergonomic',
 'CONTENT_BASED', 'PRODUCT_PAGE', 'RATING', 0.52, 1, 'PROCESSED', 39, 'user rated 1/5',
 TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP)),

-- An event that never finished processing, so "in flight" states are visible.
('EVT-SEED-0009', 'COR-SEED-0009', 'U-1009', 'ITM-2009', 'Cast Iron Skillet, 12in',
 'COLLABORATIVE_FILTER', 'HOME_FEED', 'ADD_TO_CART', 0.83, NULL, 'PROCESSING', NULL, 'in flight',
 TIMESTAMPADD(MINUTE, -4, CURRENT_TIMESTAMP), NULL),

-- The newest event, so the health endpoint's ingestion liveness check is fresh.
('EVT-SEED-0010', 'COR-SEED-0010', 'U-1010', 'ITM-2010', 'Trail Running Shoes, Size 42',
 'HYBRID', 'HOME_FEED', 'CLICK', 0.91, NULL, 'PROCESSED', 36, 'high confidence, clicked',
 TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP), TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP));


-- ---------------------------------------------------------------------------
--  Audit trail: the RECEIVED -> ... -> final timeline for each seeded event.
--  This is exactly what the status drill-down renders.
--
--  event_audit has no natural unique key (an event may legitimately re-enter a
--  status), so idempotency is handled by the scoped DELETE at the top of this
--  file rather than by a MERGE.
-- ---------------------------------------------------------------------------
INSERT INTO event_audit (event_ref, from_status, to_status, note, actor, created_at)
VALUES
('EVT-SEED-0001', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP)),
('EVT-SEED-0001', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP)),
('EVT-SEED-0001', 'PROCESSING', 'PROCESSED',  'scored in 41ms; no exception raised',     'pipeline', TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP)),

('EVT-SEED-0002', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP)),
('EVT-SEED-0002', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP)),
('EVT-SEED-0002', 'PROCESSING', 'PROCESSED',  'scored, but below the confidence floor',  'pipeline', TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP)),

('EVT-SEED-0003', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP)),
('EVT-SEED-0003', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP)),
('EVT-SEED-0003', 'PROCESSING', 'PROCESSED',  'served slowly (1720ms)',                  'pipeline', TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP)),

('EVT-SEED-0004', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP)),
('EVT-SEED-0004', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP)),
('EVT-SEED-0004', 'PROCESSING', 'FAILED',     'model timeout while scoring',             'pipeline', TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP)),

('EVT-SEED-0005', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP)),
('EVT-SEED-0005', 'RECEIVED',   'QUARANTINED','missing score for HYBRID source',         'pipeline', TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP)),

('EVT-SEED-0006', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP)),
('EVT-SEED-0006', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP)),
('EVT-SEED-0006', 'PROCESSING', 'PROCESSED',  'dismiss recorded',                        'pipeline', TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP)),

('EVT-SEED-0007', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -16, CURRENT_TIMESTAMP)),
('EVT-SEED-0007', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -16, CURRENT_TIMESTAMP)),
('EVT-SEED-0007', 'PROCESSING', 'PROCESSED',  'conversion recorded',                     'pipeline', TIMESTAMPADD(HOUR, -16, CURRENT_TIMESTAMP)),

('EVT-SEED-0008', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP)),
('EVT-SEED-0008', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP)),
('EVT-SEED-0008', 'PROCESSING', 'PROCESSED',  'rating 1/5 recorded',                     'pipeline', TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP)),

('EVT-SEED-0009', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(MINUTE, -4, CURRENT_TIMESTAMP)),
('EVT-SEED-0009', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(MINUTE, -4, CURRENT_TIMESTAMP)),

('EVT-SEED-0010', NULL,         'RECEIVED',   'event accepted from the API',             'pipeline', TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP)),
('EVT-SEED-0010', 'RECEIVED',   'PROCESSING', 'queued for scoring',                      'pipeline', TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP)),
('EVT-SEED-0010', 'PROCESSING', 'PROCESSED',  'scored in 36ms; no exception raised',     'pipeline', TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP));


-- ---------------------------------------------------------------------------
--  Alerts: one per exception code, in a mix of lifecycle states, so the alert
--  view shows OPEN, ACKNOWLEDGED and RESOLVED without any extra setup.
-- ---------------------------------------------------------------------------
INSERT INTO alert (alert_ref, event_ref, severity, code, status, message, owner,
                   resolution_note, created_at, acknowledged_at, resolved_at)
VALUES
('ALT-SEED-0001', 'EVT-SEED-0002', 'WARNING',  'LOW_CONFIDENCE',     'OPEN',
 'Recommendation score 0.12 is below the 0.30 confidence floor', NULL, NULL,
 TIMESTAMPADD(HOUR, -28, CURRENT_TIMESTAMP), NULL, NULL),

('ALT-SEED-0002', 'EVT-SEED-0003', 'WARNING',  'HIGH_LATENCY',       'ACKNOWLEDGED',
 'Serving latency of 1720ms exceeds the 500ms objective', 'sre-oncall', NULL,
 TIMESTAMPADD(HOUR, -26, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -25, CURRENT_TIMESTAMP), NULL),

('ALT-SEED-0003', 'EVT-SEED-0004', 'CRITICAL', 'SCHEMA_MISMATCH',    'OPEN',
 'Event could not be processed: model timeout while scoring', NULL, NULL,
 TIMESTAMPADD(HOUR, -22, CURRENT_TIMESTAMP), NULL, NULL),

('ALT-SEED-0004', 'EVT-SEED-0005', 'WARNING',  'STALE_EVENT',        'ACKNOWLEDGED',
 'Event is older than the 24 hour freshness budget', 'data-quality', NULL,
 TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -19, CURRENT_TIMESTAMP), NULL),

('ALT-SEED-0005', 'EVT-SEED-0006', 'INFO',     'HIGH_DISMISS_RATE',  'RESOLVED',
 'Dismiss rate on the PUSH_NOTIFICATION surface reached 40% in the last hour', 'content-ops',
 'Push cadence reduced; dismiss rate returned to 8%',
 TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -17, CURRENT_TIMESTAMP),
 TIMESTAMPADD(HOUR, -5, CURRENT_TIMESTAMP)),

('ALT-SEED-0006', 'EVT-SEED-0008', 'CRITICAL', 'RATING_DROP',        'OPEN',
 'User rated the recommendation 1/5 (threshold is 2/5)', NULL, NULL,
 TIMESTAMPADD(HOUR, -12, CURRENT_TIMESTAMP), NULL, NULL);


-- ---------------------------------------------------------------------------
--  Explicit feedback: thumbs-up/down style items linked back to events.
-- ---------------------------------------------------------------------------
INSERT INTO recommendation_feedback (feedback_ref, event_ref, user_id, item_id, rating,
                                     reason, comment, created_at)
VALUES
('FB-SEED-0001', 'EVT-SEED-0001', 'U-1001', 'ITM-2001', 5, NULL,
 'Exactly what I was looking for', TIMESTAMPADD(HOUR, -29, CURRENT_TIMESTAMP)),

('FB-SEED-0002', 'EVT-SEED-0002', 'U-1002', 'ITM-2002', 1, 'NOT_RELEVANT',
 'I already own one of these', TIMESTAMPADD(HOUR, -27, CURRENT_TIMESTAMP)),

('FB-SEED-0003', 'EVT-SEED-0008', 'U-1008', 'ITM-2008', 1, 'POOR_QUALITY',
 'Cheaper alternative shown last week', TIMESTAMPADD(HOUR, -11, CURRENT_TIMESTAMP)),

('FB-SEED-0004', 'EVT-SEED-0007', 'U-1007', 'ITM-2007', 4, NULL,
 'Good pick, slightly over budget', TIMESTAMPADD(HOUR, -15, CURRENT_TIMESTAMP));
