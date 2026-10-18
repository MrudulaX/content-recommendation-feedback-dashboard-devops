# =============================================================================
#  Disposable "clean node" used to demonstrate the provisioning playbooks.
#
#  It is deliberately minimal: a bare Debian-like node with SSH and a Docker
#  engine, i.e. exactly the starting point the Ansible roles expect before
#  site.yml turns it into a CRFD application node.
#
#    docker build -f infra/ansible/node/Dockerfile -t crfd-node:latest infra/ansible/node
#    scripts/ansible-demo.sh
#
#  docker:dind is used so the node can run the CRFD container itself; the node
#  therefore behaves like a real VM with its own Docker daemon.
# =============================================================================
FROM docker:27-dind

LABEL org.opencontainers.image.title="CRFD clean node" \
      org.opencontainers.image.description="Throwaway provisioning target: SSH + Docker, nothing else" \
      org.opencontainers.image.licenses="MIT"

# `apk upgrade` first is deliberate, not laziness: the docker:dind base image is
# periodically behind the Alpine mirror, and the pinned `openssh-client-common`
# in the base then conflicts with the `openssh` server package from the repo
# ("breaks: openssh-client-default[openssh-client-common=...]"). Bringing the
# base up to date first removes that conflict.
RUN apk upgrade --no-cache \
    && apk add --no-cache \
        bash \
        curl \
        openssh \
        python3 \
        py3-pip \
        tzdata \
        sudo \
        procps \
        ca-certificates \
    && ln -sf /usr/share/zoneinfo/UTC /etc/localtime \
    && echo "UTC" > /etc/timezone \
    # The community.docker collection runs on the node and needs the Python SDK.
    && pip install --no-cache-dir --break-system-packages "docker==7.1.0" "requests==2.32.3" \
    # Root SSH with a known password: the node is disposable and never exposed.
    && ssh-keygen -A \
    && echo 'root:crfd' | chpasswd \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && rm -rf /var/cache/apk/* /root/.cache

# Deliberately NO application artefacts, NO Docker compose file, NO user account.
# That is the point: the playbook creates all of it.

COPY entrypoint.sh /usr/local/bin/node-entrypoint.sh
RUN chmod +x /usr/local/bin/node-entrypoint.sh

EXPOSE 22 2375

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=10 \
    CMD docker info > /dev/null 2>&1 && nc -z localhost 22 || exit 1

ENTRYPOINT ["/usr/local/bin/node-entrypoint.sh"]
