#!/usr/bin/env bash
# =============================================================================
#  wait-for-health.sh - block until an HTTP endpoint reports healthy.
#
#  Usage:  scripts/wait-for-health.sh <url> [timeout-seconds] [expected-substring]
#
#  Exits 0 as soon as the endpoint answers with a 2xx status (and, when given,
#  contains the expected substring). Exits 1 on timeout. Used by the Jenkins
#  pipeline between "deploy" and "smoke test", and by the Docker compose stack.
# =============================================================================
set -u -o pipefail

URL="${1:?usage: wait-for-health.sh <url> [timeout] [expected-substring]}"
TIMEOUT="${2:-60}"
EXPECT="${3:-}"

DEADLINE=$(( $(date +%s) + TIMEOUT ))
ATTEMPT=0

echo "[wait-for-health] waiting up to ${TIMEOUT}s for ${URL}${EXPECT:+ (expecting '${EXPECT}')}"

while :; do
  ATTEMPT=$(( ATTEMPT + 1 ))
  BODY="$(curl -fsS --max-time 5 "$URL" 2>/dev/null || true)"

  if [ -n "$BODY" ]; then
    if [ -z "$EXPECT" ] || printf '%s' "$BODY" | grep -q "$EXPECT"; then
      echo "[wait-for-health] OK after ${ATTEMPT} attempt(s):"
      printf '%s\n' "$BODY"
      exit 0
    fi
  fi

  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "[wait-for-health] TIMEOUT after ${TIMEOUT}s (${ATTEMPT} attempts). Last response:"
    printf '%s\n' "${BODY:-<no response>}"
    exit 1
  fi

  sleep 2
done
