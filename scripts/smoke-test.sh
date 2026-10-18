#!/usr/bin/env bash
# =============================================================================
#  smoke-test.sh - the post-deployment acceptance smoke test.
#
#  Exercises every MVP capability against a running instance in a few seconds:
#    1. liveness / readiness          /api/v1/health
#    2. event ingest                  POST /api/v1/events
#    3. searchable dashboard query    GET  /api/v1/events?q=...
#    4. summary indicators            GET  /api/v1/metrics/summary
#    5. status drill-down             GET  /api/v1/events/{eventId}/status
#    6. alert / exception view        GET  /api/v1/alerts
#    7. dashboard UI is served        GET  /  (contains the dashboard shell)
#
#  Usage:  scripts/smoke-test.sh [base-url]      (default http://localhost:8080)
#          BASE_URL=http://localhost:8081 scripts/smoke-test.sh
#  Exit code 0 = every check passed, 1 = at least one failed.
# =============================================================================
set -u -o pipefail

BASE="${1:-${BASE_URL:-http://localhost:8080}}"
PASS=0
FAIL=0
UNIQ="$(date +%s)-$$"
ITEM="ITM-SMOKE-${UNIQ}"

log()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '   \033[1;32mPASS\033[0m  %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '   \033[1;31mFAIL\033[0m  %s\n' "$*"; }

check() { # check <description> <needle> <haystack>
  if printf '%s' "$3" | grep -q -- "$2"; then ok "$1"; else bad "$1 (expected to contain '$2')"; fi
}

log "1/7 health"
HEALTH="$(curl -fsS --max-time 10 "$BASE/api/v1/health" || true)"
check "readiness endpoint reports a healthy database" '"database":"UP"' "$HEALTH"
check "readiness endpoint reports a status" '"status":"' "$HEALTH"

log "2/7 ingest a feedback event"
INGEST="$(curl -fsS --max-time 10 -X POST "$BASE/api/v1/events" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"U-SMOKE\",\"itemId\":\"${ITEM}\",\"itemTitle\":\"Smoke test item\",\"eventType\":\"IMPRESSION\",\"source\":\"HYBRID\",\"surface\":\"HOME_FEED\",\"score\":0.88}" || true)"
check "event accepted and echoed" "$ITEM" "$INGEST"
EVENT_ID="$(printf '%s' "$INGEST" | sed -n 's/.*"eventId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [ -n "$EVENT_ID" ]; then ok "event id returned ($EVENT_ID)"; else bad "no eventId in the response"; fi

log "3/7 searchable dashboard"
SEARCH="$(curl -fsS --max-time 10 "$BASE/api/v1/events?q=${ITEM}" || true)"
check "search finds the ingested item" "$ITEM" "$SEARCH"

log "4/7 summary indicators"
SUMMARY="$(curl -fsS --max-time 10 "$BASE/api/v1/metrics/summary" || true)"
check "summary exposes a total event count" '"totalEvents"' "$SUMMARY"
check "summary exposes a click-through rate" '"clickThroughRate"' "$SUMMARY"

log "5/7 status drill-down"
if [ -n "$EVENT_ID" ]; then
  # The drill-down is GET /events/{eventId}: the event, its pipeline status and the
  # full status-transition timeline behind it.
  DETAIL="$(curl -fsS --max-time 10 "$BASE/api/v1/events/${EVENT_ID}" || true)"
  check "drill-down returns the event and its audit timeline" '"timeline"' "$DETAIL"
  check "drill-down exposes the pipeline status" '"status"' "$DETAIL"
else
  bad "drill-down skipped - no event id"
fi

log "6/7 alert / exception view"
ALERTS="$(curl -fsS --max-time 10 "$BASE/api/v1/alerts" || true)"
check "alerts endpoint returns a page of alerts" '"content"' "$ALERTS"

log "7/7 dashboard UI served"
INDEX="$(curl -fsS --max-time 10 "$BASE/" || true)"
check "dashboard shell is served" 'Content Recommendation Feedback Dashboard' "$INDEX"
check "dashboard shell wires up the client script" '/app.js' "$INDEX"

log "result: ${PASS} passed, ${FAIL} failed"
if [ "$FAIL" -gt 0 ]; then
  printf '\033[1;31mSMOKE TEST FAILED\033[0m\n'
  exit 1
fi
printf '\033[1;32mSMOKE TEST PASSED\033[0m\n'
