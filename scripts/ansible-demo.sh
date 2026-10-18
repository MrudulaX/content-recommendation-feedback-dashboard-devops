#!/usr/bin/env bash
# =============================================================================
#  ansible-demo.sh - produce the provisioning evidence end to end, on one
#  machine, without needing a spare VM.
#
#  It stands up two throwaway containers:
#
#     crfd-node        a "clean node": SSH + a Docker engine and nothing else
#                      (infra/ansible/node/Dockerfile)
#     crfd-ansible     a pinned Ansible controller
#                      (infra/ansible/Dockerfile.controller)
#
#  and then drives the real playbooks at it, capturing the logs that the report
#  needs:
#
#     evidence/ansible-01-provision-first-run.log    brings the node up
#     evidence/ansible-02-idempotency-second-run.log changed=0 on a repeat run
#     evidence/ansible-03-deploy-release.log         release 0.1.0 -> 1.0.0
#     evidence/ansible-04-healthcheck.log            health verification
#     evidence/ansible-05-rollback.log               recovery to 0.1.0
#
#  Usage:  scripts/ansible-demo.sh [evidence-dir]
# =============================================================================
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EVIDENCE="${1:-docs/evidence}"
NODE=crfd-node
CONTROLLER=crfd-ansible
NET=crfd-demo-net
APP_IMAGE=crfd:1.0.0
PREV_TAG=0.1.0

log()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m!! %s\033[0m\n' "$*"; exit 1; }

mkdir -p "$EVIDENCE"

# ---------------------------------------------------------------- preconditions
docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable"

if ! docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
  log "application image $APP_IMAGE is missing - building it"
  bash scripts/docker-lifecycle.sh build || die "could not build $APP_IMAGE"
fi

# ------------------------------------------------------------- build the images
log "building the disposable node image and the pinned Ansible controller"
docker build -q -f infra/ansible/node/Dockerfile -t crfd-node:latest infra/ansible/node || die "node image build failed"
docker build -q -f infra/ansible/Dockerfile.controller -t crfd-ansible:latest infra/ansible || die "controller image build failed"

# --------------------------------------------------------------- clean up first
log "removing any leftovers from an earlier run"
docker rm -f "$NODE" "$CONTROLLER" >/dev/null 2>&1 || true
docker network rm "$NET" >/dev/null 2>&1 || true

docker network create "$NET" >/dev/null
log "created network $NET"

# ------------------------------------------------------------------ start node
log "starting the clean node ($NODE)"
docker run -d --privileged --name "$NODE" --network "$NET" --network-alias "$NODE" \
  crfd-node:latest >/dev/null

log "waiting for the node's SSH and Docker daemon"
for i in $(seq 1 60); do
  if docker exec "$NODE" sh -c 'docker info >/dev/null 2>&1 && nc -z localhost 22' 2>/dev/null; then
    echo "   node is ready after ${i}s"
    break
  fi
  [ "$i" -eq 60 ] && die "the node never became ready"
  sleep 1
done

# The node's Docker daemon is its own; load the application image into it and
# give it a second, older tag so the rollback steps have a real target.
log "loading $APP_IMAGE into the node and tagging it as ${APP_IMAGE%%:*}:${PREV_TAG}"
docker save "$APP_IMAGE" | docker exec -i "$NODE" docker load >/dev/null
docker exec "$NODE" docker tag "$APP_IMAGE" "crfd:${PREV_TAG}"
docker exec "$NODE" docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}'

# ------------------------------------------------------------------- controller
# The controller stages the mount into a private copy itself (see
# infra/ansible/entrypoint.controller.sh), so there is deliberately no -w here:
# a bind mount from a permissive host arrives world-writable, and Ansible then
# silently refuses to read ansible.cfg from it.
#
# demo-overrides.yml is applied as extra vars (-e @file). Extra vars outrank
# every other source, which is required here: demo values written into the
# inventory file would lose to inventory/group_vars/all.yml and the demo would
# try to pull its image from Docker Hub.
run_and_collect() {
  local label="$1"; shift
  local tmp="infra/ansible/.run-${label}.log"
  set +e
  docker run --rm --network "$NET" \
    -v "$ROOT/infra/ansible:/ansible" \
    -e ANSIBLE_FORCE_COLOR=0 \
    crfd-ansible:latest \
    "ansible-playbook -i inventory/hosts.demo.ini -e @inventory/demo-overrides.yml $* 2>&1" \
    > "$tmp" 2>&1
  local rc=$?
  set -e
  mv "$tmp" "$EVIDENCE/${label}.log" 2>/dev/null || true
  return $rc
}

declare -i failures=0

# ------------------------------------------------------------------ 1 first run
log "STEP 1/5 - provision the clean node with the MVP release"
if run_and_collect "ansible-01-provision-first-run" "playbooks/site.yml -e app_version=${PREV_TAG}"; then
  echo "   OK"
else
  warn "step 1 failed - see $EVIDENCE/ansible-01-provision-first-run.log"
  failures+=1
fi
grep -E 'PLAY RECAP' -A 3 "$EVIDENCE/ansible-01-provision-first-run.log" | tail -5 || true

# ------------------------------------------------------- 2 idempotency, 2nd run
log "STEP 2/5 - run the same playbook again; expect changed=0"
if run_and_collect "ansible-02-idempotency-second-run" "playbooks/site.yml -e app_version=${PREV_TAG}"; then
  echo "   OK"
else
  warn "step 2 failed - see $EVIDENCE/ansible-02-idempotency-second-run.log"
  failures+=1
fi
grep -E 'PLAY RECAP' -A 3 "$EVIDENCE/ansible-02-idempotency-second-run.log" | tail -5 || true

# -------------------------------------------------------------- 3 new release
log "STEP 3/5 - deploy release 1.0.0 (records 0.1.0 as the rollback point)"
if run_and_collect "ansible-03-deploy-release" "playbooks/deploy.yml -e app_version=1.0.0"; then
  echo "   OK"
else
  warn "step 3 failed - see $EVIDENCE/ansible-03-deploy-release.log"
  failures+=1
fi

# ------------------------------------------------------------------ 4 health
log "STEP 4/5 - independent health verification"
if run_and_collect "ansible-04-healthcheck" "playbooks/healthcheck.yml"; then
  echo "   OK"
else
  warn "step 4 failed - see $EVIDENCE/ansible-04-healthcheck.log"
  failures+=1
fi

# ---------------------------------------------------------------- 5 rollback
log "STEP 5/5 - recovery: roll back to the recorded release (0.1.0)"
if run_and_collect "ansible-05-rollback" "playbooks/rollback.yml"; then
  echo "   OK"
else
  warn "step 5 failed - see $EVIDENCE/ansible-05-rollback.log"
  failures+=1
fi

# ------------------------------------------------------------------- summary
log "evidence written to $EVIDENCE"
ls -l "$EVIDENCE"/ansible-*.log 2>/dev/null || true

log "container states on the node"
docker exec "$NODE" docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' || true

log "rollback bookkeeping on the node"
docker exec "$NODE" sh -c 'cat /etc/crfd/previous-release.txt 2>/dev/null; echo ---; cat /etc/crfd/release-history.log 2>/dev/null' || true

if [ "$failures" -gt 0 ]; then
  warn "${failures} step(s) failed - inspect the logs above"
  exit 1
fi
printf '\033[1;32mALL PROVISIONING STEPS PASSED\033[0m\n'
