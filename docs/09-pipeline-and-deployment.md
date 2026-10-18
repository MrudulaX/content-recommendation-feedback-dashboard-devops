# 09 — The build pipeline and the deployment

`docs/08` covers the Jenkins controller and what each stage does. This document is
about the *deployment* half: what artefact is produced, where it is deployed, how
the deployment is verified, and how it is undone.

## 1. The artefact

One Maven module, `jar` packaging by default, built by the pinned wrapper
(Maven 3.9.16):

```bash
./mvnw -B clean package
# target/content-recommendation-feedback-dashboard.jar
java -jar target/content-recommendation-feedback-dashboard.jar
```

A Maven **profile** changes the shape without touching the code:

| Profile | What it changes | Output |
| --- | --- | --- |
| *(none)* | Spring Boot fat JAR, embedded Tomcat | `target/content-recommendation-feedback-dashboard.jar` |
| `-P war` | `packaging: war`, repackages as a deployable webapp, `finalName` `crfd` | `target/crfd.war` |
| `-P mysql` | Activates the `mysql` Spring profile (MySQL datasource) | — |
| `-P selenium` | Adds failsafe and runs the Selenium journeys during `verify` | — |

```bash
./mvnw -B -P war clean package        # target/crfd.war
cp target/crfd.war "$CATALINA_HOME/webapps/"
```

The `war` profile is not decoration. It demonstrates that the application is not
coupled to its embedded container, which is the substantive claim behind "Tomcat
deployment target". The same code runs three ways:

```
A. java -jar app.jar                     embedded Tomcat, no proxy
B. docker compose up -d                  container + Nginx, what the pipeline deploys
C. cp crfd.war $CATALINA_HOME/webapps/   external Tomcat 10.1
```

## 2. Deployment topology (shape B — what the pipeline deploys)

```
                 ┌──────────────────────────────────────────────┐
   browser ──────▶│  crfd-nginx      :8081 → :80                 │
   (human)        │    /            → app:8080                   │
                 │    /healthz      → app:8080/api/v1/health     │
                 │    /api/v1/events  rate-limited 25 r/s        │
                 └───────────────┬──────────────────────────────┘
                                 │ crfd-net (pinned bridge)
                 ┌───────────────▼──────────────────────────────┐
   部署 target    │  crfd-app      image crfd:<tag>              │
                 │    non-root uid 100(crfd), JRE 21 only        │
                 │    HEALTHCHECK → /api/v1/health               │
                 │    volume crfd-data:/app/crfd-data            │
                 └──────────────────────────────────────────────┘
                                 │
                 ┌───────────────▼──────────────────────────────┐
   verification  │  crfd-live     same image, alias on crfd-ci-net│
                 │  (the pipeline probes THIS, by name)          │
                 └──────────────────────────────────────────────┘
```

Separating the *deployed* container's port from the pipeline's probe is the whole
point of the `crfd-live` alias. The port is for a human; the alias is for the
pipeline. Asking the pipeline to use the published port is what breaks as soon as
Jenkins itself lives in a container.

## 3. What "deploy" does

Deployment is intentionally crude and reproducible:

```bash
docker rm -f crfd-app || true
docker run -d --name crfd-app \
    --restart unless-stopped \
    --network crfd-ci-net --network-alias crfd-live \
    -p ${APP_PORT}:8080 \
    -e CRFD_ENVIRONMENT=${ENVIRONMENT} \
    -e CRFD_SIMULATION_ENABLED=... \
    -e CRFD_SEED_ON_STARTUP=... \
    -e CRFD_LOW_CONFIDENCE=${CRFD_LOW_CONFIDENCE} \
    -e CRFD_CRITICAL_LATENCY_MS=${CRFD_CRITICAL_LATENCY_MS} \
    crfd:${IMAGE_TAG}
```

Three properties make it safe rather than merely short:

**It is replaceable, not mutable.** The old container is removed and a new one is
created from an immutable, versioned image. Nothing is patched inside a running
container, so "which code is running?" always has the same answer: the image tag.

**The data survives.** Event data lives on the `crfd-data` volume, so removing and
recreating the container loses nothing. This is what makes the crude replace safe
(proved in `docs/12`).

**Configuration is injected, not baked.** The environment, the thresholds and the
simulation switches arrive as environment variables. The same image is deployed to
`dev`, `staging` and `production`; only the parameters differ, and in production
the pipeline turns synthetic traffic and seeding **off**.

## 4. Verification after deployment

A deployment that returns without checking anything is a hope, not a deployment.
The pipeline verifies in four steps, each catching a different failure:

| Step | Check | Catches |
| --- | --- | --- |
| 1 | `scripts/wait-for-health.sh http://crfd-live:8080/api/v1/health 120` | The container is up but the application never finished starting, or a hard dependency is down |
| 2 | `curl -fsS /api/v1/health` | The endpoint answers, and the payload is archived as evidence |
| 3 | `POST /api/v1/events` then `GET /api/v1/events?q=ITM-SMOKE` and `grep -q ITM-SMOKE` | The *write* path works end to end. A dashboard that renders but cannot ingest is broken in the one way that matters |
| 4 | `GET /api/v1/metrics/summary` | The read model can aggregate |

Step 3 is the one people skip, and it is the important one. A readiness endpoint
can be green while the ingestion path is broken — a missing index, a rejected
schema, a misconfigured datasource — and only a real write-then-read round trip
notices.

Note that step 1 gates on `/api/v1/health`, the **readiness** endpoint, which
answers `200` for `DEGRADED` and `503` only for `DOWN`. That is deliberate: a
pipeline success-rate dip must not fail an otherwise good deployment. If the SLO
breach should block a release in a particular environment, it is a separate,
opt-in gate on `/api/v1/health/slo` — and it should be opt-in, because tying
release success to a business metric means a bad data day blocks every hotfix.

## 5. Rolling deployment, for more than one node

The Docker path replaces a single container, which is correct for a single-host
MVP. Where there is more than one node, `playbooks/deploy.yml` does the rolling
version:

```yaml
- hosts: appservers
  serial: "{{ deploy_serial | default(1) }}"
  max_fail_percentage: 0
  any_errors_fatal: true
```

- **`serial: 1`** — one node at a time, so the estate keeps serving.
- **`max_fail_percentage: 0`** — the first node that fails stops the play before
  the next node is touched. This is "a failed health check blocks the rollout",
  implemented as configuration rather than as a comment.
- **Role-level health wait** — the `crfd_app` role polls `/api/v1/health` after
  each node's container starts, so a node that comes up broken is never followed by
  the next one.

## 6. Rollback

The Docker path rolls back by deploying a previous image tag — the image is still
in the local daemon, so it is a `docker run` away:

```bash
docker run -d --name crfd-app ... crfd:<previous-tag>
```

The Ansible path is more interesting because it has to *remember* what the previous
release was. The `crfd_app` role writes the outgoing version to
`/etc/crfd/previous-release.txt` **only when the version actually changes**, and
appends the transition to `/etc/crfd/release-history.log`. Two consequences:

1. A repeat run of the same playbook changes nothing, which is the `changed=0`
   idempotency evidence.
2. After a rollback, the state file names the release just left behind — so
   rolling back leaves the node able to roll *forward*. Recovery is symmetric
   rather than a one-shot fix.

Recorded history from a real demo run:

```
previous-release.txt: 1.0.0
release-history.log:
  2026-10-18T12:41:05Z  0.1.0 -> 1.0.0  (by ansible, host=crfd-node)
  2026-10-18T12:41:41Z  1.0.0 -> 0.1.0  (by ansible, host=crfd-node)
```

Full detail and the run logs: `docs/14-ansible-provisioning.md`.

## 7. Nginx: why it is in front

Nginx is not there to look like production. Each of its jobs is a concern that
would otherwise have to live in the application:

| Job | Config | Why it does not belong in the app |
| --- | --- | --- |
| Single stable entry point | `server_name _`, upstream `app:8080` | The app container is replaced on every deploy; the address clients use must not change |
| Readiness probe for infrastructure | `location = /healthz → /api/v1/health` | Deployment tooling needs one URL that is not an API path |
| Rate limiting the ingest path | `limit_req zone=crfd_ingest rate=25r/s burst=50` | Abuse protection is an edge concern; the application should not implement its own rate limiter |
| Connection reuse to the app | `keepalive 16`, `proxy_http_version 1.1` | Connection pooling to the upstream belongs in the proxy |
| Security headers | `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` | Uniform response hygiene, applied once |
| Static asset caching | `expires 1h` for `css/js/svg/ico` | Content-type policy, not business logic |

The rate limit has a specific motivation: `/api/v1/events` is the write path, so it
is the endpoint a runaway producer or an abusive client will hammer. `burst=50
nodelay` absorbs a legitimate spike while a sustained flood is rejected at the
edge, before it can occupy an application thread.

Note also `client_max_body_size 4m` — the bulk endpoint accepts an array, so an
unbounded body is a real denial-of-service vector.

## 8. Where the deployment target is configured

| Concern | Where |
| --- | --- |
| Port mapping | `docker-compose.yml` (local), `APP_PORT` parameter (pipeline and Ansible) |
| Environment name | `CRFD_ENVIRONMENT`, from the `ENVIRONMENT` parameter |
| Thresholds | `CRFD_*` environment variables, from pipeline parameters |
| Simulation and seeding | On by default for dev; **forced off** in production by the pipeline |
| Nginx upstream | `infra/nginx/nginx.conf` |
| Ansible inventory | `inventory/hosts.ini` per target (a committed example is `hosts.example.ini`) |

## 9. Pipeline-versus-deployment summary

```
 build            package           containerise        gate               deploy            verify
 ─────            ────────          ────────────        ────               ──────            ──────
 mvnw clean  ───▶ JAR + sha256  ───▶ crfd:<tag>    ───▶ Selenium 5/5  ───▶ docker run   ───▶ /health
 verify              archived        (+git sha)         30 tests           fresh container     write+read
 32 tests             + build-info   image-inspect.json +screenshots       on crfd-ci-net      summary
                          │                                    │                  │
                          │                                    │                  ▼
                          │                             FAILS? STOP HERE      rollback:
                          ▼                             (nothing published,   re-run the
                     archived by Jenkins                nothing deployed)     previous tag
```

Every arrow to the right is crossed only after everything to its left has passed,
and the two irreversible actions — publishing an image and replacing a running
container — are the last two things the pipeline does.
