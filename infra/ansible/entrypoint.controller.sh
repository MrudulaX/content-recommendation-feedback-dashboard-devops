#!/usr/bin/env bash
# =============================================================================
#  Entrypoint for the pinned Ansible controller image.
#
#  Why this exists at all: the project directory is bind-mounted into the
#  controller, and a bind mount coming from a Windows (or otherwise permissive)
#  host arrives with mode 777. Ansible refuses to read `ansible.cfg` from a
#  world-writable working directory. It only emits a warning and then carries on
#  with built-in defaults, which means `roles_path` and `inventory` are silently
#  dropped and the run dies with the thoroughly misleading:
#
#      ERROR! the role 'common' was not found in
#      /ansible/playbooks/roles:/root/.ansible/roles:...
#
#  Nothing in the playbooks is wrong in that case; the configuration was simply
#  never loaded. Rather than ask every operator to remember `chmod`, the
#  controller stages the mounted tree into a private copy under /work before
#  running anything. Because every path inside the playbooks is relative, the
#  playbooks cannot tell the difference - which is the point.
#
#  The original mount at /ansible is left untouched, so the evidence logs and
#  any generated files still land in the operator's working tree.
# =============================================================================
set -euo pipefail

if [ -d /ansible ]; then
    rm -rf /work
    mkdir -p /work
    cp -a /ansible/. /work/
    # Kill world/group write on the copy so Ansible's config file check passes.
    chmod -R go-w /work
    cd /work
fi

# The CMD (or the caller's argument) is a shell command line, so run it through
# bash -lc exactly as the previous image did.
exec /bin/bash -lc "$*"
