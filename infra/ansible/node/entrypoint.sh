#!/usr/bin/env bash
# =============================================================================
#  Entrypoint for the disposable provisioning node.
#
#  Starts the Docker daemon (the node's own engine) and sshd, so that Ansible
#  can reach the node over SSH exactly as it would reach a real VM.
# =============================================================================
set -euo pipefail

echo "[node] starting dockerd ..."
dockerd \
    --host=unix:///var/run/docker.sock \
    --host=tcp://0.0.0.0:2375 \
    --tls=false \
    > /var/log/dockerd.log 2>&1 &

# Wait for the engine before handing the node to Ansible.
for i in $(seq 1 60); do
    if docker info > /dev/null 2>&1; then
        echo "[node] docker engine is up: $(docker version --format '{{.Server.Version}}')"
        break
    fi
    sleep 1
done

if ! docker info > /dev/null 2>&1; then
    echo "[node] FATAL: docker engine did not start; last log lines:"
    tail -n 40 /var/log/dockerd.log || true
    exit 1
fi

echo "[node] starting sshd ..."
mkdir -p /run/sshd
exec /usr/sbin/sshd -D -e
