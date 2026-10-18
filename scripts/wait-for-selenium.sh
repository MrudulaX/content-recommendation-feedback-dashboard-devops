#!/usr/bin/env bash
# =============================================================================
#  wait-for-selenium.sh - block until the Selenium Grid / standalone hub is
#  accepting sessions (its /status endpoint reports "ready": true).
#
#  Usage:  scripts/wait-for-selenium.sh <hub-status-url> [timeout-seconds]
#  e.g.    scripts/wait-for-selenium.sh http://localhost:4444/wd/hub/status 90
# =============================================================================
set -u -o pipefail

URL="${1:?usage: wait-for-selenium.sh <hub-status-url> [timeout]}"
TIMEOUT="${2:-90}"

DEADLINE=$(( $(date +%s) + TIMEOUT ))
ATTEMPT=0

echo "[wait-for-selenium] waiting up to ${TIMEOUT}s for ${URL}"

while :; do
  ATTEMPT=$(( ATTEMPT + 1 ))
  BODY="$(curl -fsS --max-time 5 "$URL" 2>/dev/null || true)"

  if printf '%s' "$BODY" | grep -q '"ready"[: ]*true'; then
    echo "[wait-for-selenium] hub ready after ${ATTEMPT} attempt(s)"
    exit 0
  fi

  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "[wait-for-selenium] TIMEOUT after ${TIMEOUT}s. Last response:"
    printf '%s\n' "${BODY:-<no response>}"
    exit 1
  fi

  sleep 2
done
