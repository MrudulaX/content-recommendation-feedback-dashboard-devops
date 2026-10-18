#!/usr/bin/env bash
# =============================================================================
#  docker-lifecycle.sh - the complete container lifecycle for the CRFD image,
#  as one reproducible script. This is the artefact behind the "Docker
#  lifecycle: build, tag, run, logs, stop, restart, remove" evidence.
#
#  Usage:
#     scripts/docker-lifecycle.sh all            # the whole lifecycle in order
#     scripts/docker-lifecycle.sh build
#     scripts/docker-lifecycle.sh tag
#     scripts/docker-lifecycle.sh run
#     scripts/docker-lifecycle.sh logs
#     scripts/docker-lifecycle.sh ps
#     scripts/docker-lifecycle.sh health
#     scripts/docker-lifecycle.sh exec
#     scripts/docker-lifecycle.sh stop
#     scripts/docker-lifecycle.sh start
#     scripts/docker-lifecycle.sh restart
#     scripts/docker-lifecycle.sh remove
#     scripts/docker-lifecycle.sh rmi
#     scripts/docker-lifecycle.sh clean           # remove container + image
#
#  Environment:
#     IMAGE       image name            (default crfd)
#     TAG         primary tag           (default 1.0.0)
#     CONTAINER   container name        (default crfd-app)
#     HOST_PORT   published host port   (default 8080)
#     ENVIRONMENT crfd.environment      (default docker)
# =============================================================================
set -u -o pipefail

IMAGE="${IMAGE:-crfd}"
TAG="${TAG:-1.0.0}"
CONTAINER="${CONTAINER:-crfd-app}"
HOST_PORT="${HOST_PORT:-8080}"
ENVIRONMENT="${ENVIRONMENT:-docker}"
BINARY="./target/content-recommendation-feedback-dashboard.jar"
FULL="${IMAGE}:${TAG}"
BASE="http://localhost:${HOST_PORT}"

log()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*"; }

ensure_binary() {
  if [ ! -f "$BINARY" ]; then
    warn "$BINARY missing - packaging it first"
    ./mvnw -B -DskipTests clean package
  else
    log "using existing binary $BINARY ($(du -h "$BINARY" | cut -f1))"
  fi
}

do_build() {
  ensure_binary
  log "docker build -t ${FULL} -t ${IMAGE}:latest ."
  docker build \
    --build-arg APP_VERSION=1.0.0-SNAPSHOT \
    --build-arg BUILD_REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo local)" \
    -t "${FULL}" -t "${IMAGE}:latest" .
  docker images --filter "reference=${IMAGE}" \
    --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}'
}

do_tag() {
  local rev; rev="$(git rev-parse --short HEAD 2>/dev/null || echo local)"
  log "tagging ${FULL} as ${IMAGE}:${rev} and ${IMAGE}:stable"
  docker tag "${FULL}" "${IMAGE}:${rev}"
  docker tag "${FULL}" "${IMAGE}:stable"
  docker images --filter "reference=${IMAGE}" --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}'
}

do_run() {
  log "removing any previous container named ${CONTAINER}"
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  log "docker run -d --name ${CONTAINER} -p ${HOST_PORT}:8080"
  docker run -d \
    --name "${CONTAINER}" \
    --restart unless-stopped \
    -p "${HOST_PORT}:8080" \
    -e CRFD_ENVIRONMENT="${ENVIRONMENT}" \
    -e CRFD_SIMULATION_ENABLED=true \
    -e CRFD_SEED_ON_STARTUP=true \
    -v crfd-data:/app/crfd-data \
    "${FULL}"
  do_ps
}

do_ps() {
  log "container status"
  docker ps -a --filter "name=${CONTAINER}" \
    --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
}

do_logs() {
  log "last 40 log lines from ${CONTAINER}"
  docker logs --tail 40 "${CONTAINER}"
}

do_health() {
  log "container health (docker inspect) + application health (HTTP)"
  docker inspect --format '{{.State.Status}} / health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "${CONTAINER}" || true
  curl -fsS "${BASE}/api/v1/health" && echo || warn "health endpoint not reachable yet"
}

do_exec() {
  log "exec: proving the runtime image is a slim JRE (no JDK, no Maven)"
  docker exec "${CONTAINER}" java -version
  docker exec "${CONTAINER}" sh -c 'command -v mvn || echo "(no maven in runtime image - as intended)"'
  docker exec "${CONTAINER}" sh -c 'ls -la /app && ls -la /app/crfd-data'
}

do_stop()    { log "docker stop ${CONTAINER}";    docker stop "${CONTAINER}"; do_ps; }
do_start()   { log "docker start ${CONTAINER}";   docker start "${CONTAINER}"; do_ps; }
do_restart() { log "docker restart ${CONTAINER}"; docker restart "${CONTAINER}"; do_ps; }
do_remove()  { log "docker rm -f ${CONTAINER}";   docker rm -f "${CONTAINER}"; do_ps; }
do_rmi()     { log "docker rmi ${FULL}";          docker rmi "${FULL}" || true; }
do_clean()   { do_remove || true; do_rmi || true; }

case "${1:-all}" in
  build)   do_build ;;
  tag)     do_tag ;;
  run)     do_run ;;
  logs)    do_logs ;;
  ps)      do_ps ;;
  health)  do_health ;;
  exec)    do_exec ;;
  stop)    do_stop ;;
  start)   do_start ;;
  restart) do_restart ;;
  remove)  do_remove ;;
  rmi)     do_rmi ;;
  clean)   do_clean ;;
  all)
    do_build; do_tag; do_run
    log "waiting for the application inside the container to become healthy"
    bash scripts/wait-for-health.sh "${BASE}/api/v1/health" 120
    do_logs; do_health; do_exec
    log "restarting the container to prove state survives on the volume"
    do_restart
    bash scripts/wait-for-health.sh "${BASE}/api/v1/health" 120
    log "stopping + starting again"
    do_stop; do_start
    bash scripts/wait-for-health.sh "${BASE}/api/v1/health" 120
    log "lifecycle complete. Container left running as ${CONTAINER} on port ${HOST_PORT}."
    ;;
  *)
    echo "unknown command: $1" >&2
    sed -n '3,30p' "$0"
    exit 2 ;;
esac
