#!/usr/bin/env bash
# =============================================================================
#  run-selenium.sh - run the Selenium end-to-end suite.
#
#  Three modes are supported, matching the three places the suite is executed:
#
#    local    : a browser installed on this machine drives the app on localhost
#               (used during development).         scripts/run-selenium.sh local
#
#    remote   : a Selenium container drives the app, both on a shared Docker
#               network. This is exactly what Jenkins does.
#                                               scripts/run-selenium.sh remote
#
#    compose  : the whole stack (app + nginx + selenium) from docker-compose,
#               for a full end-to-end demonstration. scripts/run-selenium.sh compose
#
#  Environment overrides: APP_PORT, SELENIUM_PORT, BASE_URL, HEADLESS, BROWSER.
# =============================================================================
set -u -o pipefail

MODE="${1:-remote}"
APP_PORT="${APP_PORT:-8080}"
APP_CONTAINER="${APP_CONTAINER:-crfd-app}"
SELENIUM_PORT="${SELENIUM_PORT:-4444}"
SELENIUM_CONTAINER="${SELENIUM_CONTAINER:-crfd-selenium}"
NETWORK="${NETWORK:-crfd-selenium-net}"
SELENIUM_IMAGE="${SELENIUM_IMAGE:-selenium/standalone-chrome:4.25.0}"
BROWSER="${BROWSER:-chrome}"
HEADLESS="${HEADLESS:-true}"

log() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m!! %s\033[0m\n' "$*"; exit 1; }

run_maven_suite() {
  local base="$1" remote="${2:-}"
  local -a env_args=()
  if [ -n "$remote" ]; then
    env_args+=( "SELENIUM_REMOTE_URL=${remote}" )
  fi
  log "mvn -P selenium verify  (baseUrl=${base}${remote:+, remote=${remote}})"
  env "${env_args[@]}" \
    SELENIUM_BROWSER="${BROWSER}" \
    mvn -B -P selenium verify \
      -Dselenium.baseUrl="${base}" \
      -Dselenium.headless="${HEADLESS}" \
      -Dselenium.browser="${BROWSER}"
}

case "$MODE" in

  local)
    log "mode: local browser against http://localhost:${APP_PORT}"
    run_maven_suite "${BASE_URL:-http://localhost:${APP_PORT}}" ""
    ;;

  remote)
    log "mode: Selenium container against the application container (Jenkins path)"

    docker network create "${NETWORK}" >/dev/null 2>&1 || true

    if ! docker ps --format '{{.Names}}' | grep -qx "${APP_CONTAINER}"; then
      warn_msg="app container ${APP_CONTAINER} is not running - starting it on ${NETWORK}"
      printf '\033[1;33m!! %s\033[0m\n' "$warn_msg"
      docker rm -f "${APP_CONTAINER}" >/dev/null 2>&1 || true
      docker run -d --name "${APP_CONTAINER}" --network "${NETWORK}" \
        --network-alias crfd-app -p "${APP_PORT}:8080" \
        -e CRFD_ENVIRONMENT=selenium -e CRFD_SEED_ON_STARTUP=true -e CRFD_SIMULATION_ENABLED=true \
        crfd:1.0.0
      docker network connect "${NETWORK}" "${APP_CONTAINER}" 2>/dev/null || true
    fi

    docker rm -f "${SELENIUM_CONTAINER}" >/dev/null 2>&1 || true
    log "starting ${SELENIUM_IMAGE}"
    docker run -d --name "${SELENIUM_CONTAINER}" \
      --network "${NETWORK}" \
      --shm-size=2g \
      -p "${SELENIUM_PORT}:4444" -p 7900:7900 \
      "${SELENIUM_IMAGE}" >/dev/null

    bash scripts/wait-for-health.sh "http://localhost:${APP_PORT}/api/v1/health" 120
    bash scripts/wait-for-selenium.sh "http://localhost:${SELENIUM_PORT}/wd/hub/status" 90

    # The browser lives inside the Selenium container, so it must address the
    # application by its network alias, not by localhost.
    run_maven_suite "http://crfd-app:8080" "http://localhost:${SELENIUM_PORT}/wd/hub"
    rc=$?

    log "collecting container logs into target/"
    mkdir -p target
    docker logs "${APP_CONTAINER}" > target/selenium-app-under-test.log 2>&1 || true
    docker rm -f "${SELENIUM_CONTAINER}" >/dev/null 2>&1 || true
    exit $rc
    ;;

  compose)
    log "mode: full compose stack (app + nginx + selenium)"
    docker compose -f docker-compose.yml -f infra/selenium/docker-compose.selenium.yml up -d --build
    # From the host we check nginx; the browser itself reaches the app in-network.
    bash scripts/wait-for-health.sh "http://localhost:8081/healthz" 150
    run_maven_suite "${BASE_URL:-http://crfd-app:8080}" "http://localhost:${SELENIUM_PORT}/wd/hub"
    rc=$?
    docker compose -f docker-compose.yml -f infra/selenium/docker-compose.selenium.yml down -v || true
    exit $rc
    ;;

  *)
    fail "unknown mode '${MODE}' (expected: local | remote | compose)" ;;
esac
