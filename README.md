# Content Recommendation Feedback Dashboard

A DevOps project built around one working product: a **feedback dashboard for a
content recommendation pipeline**. Recommendation feedback events are ingested,
processed, and then queried, summarised, drilled into and alerted on through a
web dashboard — and the whole thing is delivered by a Git → Jenkins → Selenium →
Docker → Ansible toolchain.

This repository is the complete deliverable: application, tests, pipeline,
container image, provisioning playbooks, database scripts and the written report.

---

## Table of contents

- [What the product does](#what-the-product-does)
- [Screens and capabilities](#screens-and-capabilities)
- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [API catalogue](#api-catalogue)
- [Configuration](#configuration)
- [Testing](#testing)
- [Docker](#docker)
- [Jenkins (CI/CD)](#jenkins-cicd)
- [Selenium (end-to-end)](#selenium-end-to-end)
- [Provisioning with Ansible](#provisioning-with-ansible)
- [Database](#database)
- [Repository layout](#repository-layout)
- [Git workflow](#git-workflow)
- [Documentation index](#documentation-index)
- [Known limitations](#known-limitations)
- [Licence](#licence)

---

## What the product does

A recommender serves items to users across several surfaces (home feed, product
page, search results, email, push). Every time a recommendation is served and
reacted to, a **feedback event** is produced. Those events are the raw material
for answering three questions:

1. **Is the pipeline healthy?** — how many events are arriving, how many fail,
   how slow is serving, how many exceptions are open.
2. **What happened to *this* recommendation?** — a per-event drill-down showing
   the event's state, its full status-transition timeline and any exceptions it raised.
3. **What needs attention right now?** — an exception work queue an operator can
   acknowledge and resolve.

The MVP covers six frozen capabilities:

| ID | Capability |
| --- | --- |
| CRFD-101 | Data / event entry (single and bulk ingestion, idempotent) |
| CRFD-102 | Searchable dashboard (free text + facets, paging, CSV export) |
| CRFD-103 | Summary indicators (KPIs, time series, status/source/surface breakdowns) |
| CRFD-104 | Status drill-down (event detail, audit timeline, related alerts, manual transition) |
| CRFD-105 | Alert / exception view (filter, acknowledge, resolve, severity) |
| CRFD-106 | Explicit feedback capture and analysis |

---

## Screens and capabilities

The UI is a single page (`src/main/resources/static/index.html`) with no build
step, so the deployable artefact stays exactly one JAR.

| Screen | What it shows |
| --- | --- |
| **Dashboard** | KPI strip (total / processed / p95 latency / open exceptions), throughput time series, status donut, source and surface breakdowns, deep-health pill, environment badge |
| **Event table** | Searchable, faceted, sortable, paged; CSV export of the current filter |
| **Event entry modal** | Submits a feedback event and reports the accepted business key |
| **Drill-down drawer** | Event fields, status chips, audit timeline, related alerts, manual status transition |
| **Exception queue** | Open alerts with severity, code, owner; one-click acknowledge and resolve |

---

## Quick start

### Option A — one command with Docker Compose (recommended)

```bash
docker compose up -d --build
```

| Endpoint | URL |
| --- | --- |
| Dashboard (through Nginx) | http://localhost:8081 |
| Application (direct) | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| H2 console | http://localhost:8080/h2-console |
| Actuator health | http://localhost:8080/actuator/health |

```bash
docker compose logs -f app     # follow the application log
docker compose down            # stop and remove the containers
docker compose down -v         # ... and remove the data volume
```

### Option B — run the JAR directly (Windows)

```powershell
cd content-recommendation-feedback-dashboard
.\scripts\local-run.ps1                       # packages if needed, then starts on 18080
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
```

### Option C — run the JAR directly (macOS / Linux)

```bash
./mvnw -B -DskipTests clean package
java -jar target/content-recommendation-feedback-dashboard.jar --server.port=18080
bash scripts/smoke-test.sh http://localhost:18080
```

> **Port 8080 is often already taken.** Every script accepts an override; the
> local default is **18080**. See `docs/17-troubleshooting-guide.md`.

### Ingest your first event

```bash
curl -X POST http://localhost:18080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": "U-1001",
        "itemId": "ITM-2001",
        "itemTitle": "Deep Learning for Practitioners",
        "eventType": "CLICK",
        "source": "HYBRID",
        "surface": "HOME_FEED",
        "score": 0.93
      }'
```

The response echoes the event with the **business key** the server minted for it
(`EVT-…`). Search for that key or the `itemId` in the dashboard.

---

## Architecture

```
                      ┌───────────────────────────────────────────────┐
   product surfaces   │            CRFD application (one JAR)         │
  ┌──────────────┐    │                                               │
  │  Home feed   │    │  REST API        (controller layer)           │
  │  Product page│───▶│      │                                        │
  │  Search /    │    │      ▼                                        │
  │  Email/Push  │    │  EventService ──▶ PipelineScheduler           │
  └──────────────┘    │      │                   │                    │
                      │      ▼                   ▼                    │
                      │  EventProcessingService  AlertService         │
                      │      │                   │                    │
                      │      └───────┬───────────┘                    │
                      │              ▼                                │
                      │        MetricsService  HealthService          │
                      │              │                                │
                      │              ▼                                │
                      │     H2 (file) / MySQL  +  static dashboard UI │
                      └───────────────────────────────────────────────┘
                                       ▲
                                       │ reverse proxy, /healthz
                                  ┌────┴────┐
                                  │  Nginx  │
                                  └─────────┘
```

**Ingestion path.** `POST /api/v1/events` validates the request, mints a
business key if the client did not supply one, rejects duplicates with a `409`
(and raises a `DUPLICATE_EVENT` alert as evidence), persists the event as
`RECEIVED`, and hands it to `PipelineScheduler`.

**Processing.** `PipelineScheduler` dispatches to `EventProcessingService`, which
runs in its own transaction (`REQUIRES_NEW`) so that a write scheduled from a
transaction-commit hook is never silently discarded. The pipeline simulates
scoring latency, then moves the event to `PROCESSED`, `FAILED` or `QUARANTINED`,
appending an immutable `AuditEntry` for every transition.

**Exception raising.** Each terminal transition is evaluated against the
configured thresholds (confidence floor, latency objective, staleness budget,
dismiss rate, rating floor), and `AlertService` raises an alert when one is
breached.

**Read models.** `MetricsService` serves the summary indicators and the time
series; `HealthService` serves the readiness payload. Both are read-only and
index-backed.

### Readiness vs. the SLO gate

Deliberately two different endpoints, because they answer two different questions:

| Endpoint | Question | `503` when |
| --- | --- | --- |
| `GET /api/v1/health` | Can this instance serve traffic? | a hard dependency is down |
| `GET /api/v1/health/slo` | Is the pipeline meeting its objective? | the success-rate SLO is breached |

A slipped success rate is an **operations problem to alert on**, not a reason to
take a working node out of rotation and fail an otherwise good deployment. Container
health checks, Nginx and the Jenkins/Ansible deploy gate use `/health`; monitoring
uses `/health/slo`.

---

## Technology stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language / runtime | Java 21 | LTS, records, pattern matching — no Lombok needed |
| Framework | Spring Boot 3.3.5 | Embedded Tomcat, one-JAR deployment, Actuator, OpenAPI |
| Build tool | **Maven** (wrapper 3.9.16) | Declarative profiles; the wrapper pins the version for CI and Docker |
| Database | H2 file mode (default), MySQL 8 profile | Zero-install locally, a real server profile for staging |
| Web container | Embedded Tomcat (JAR) + external Tomcat `war` profile | Both deployment shapes are demonstrated |
| Reverse proxy | Nginx 1.27 | TLS/edge concern kept out of the application |
| UI | Vanilla ES2020 + CSS, no bundler | Keeps the artefact a single JAR; no Node toolchain in the pipeline |
| Unit / integration tests | JUnit 5, Spring Boot Test, MockMvc, AssertJ | 32 tests |
| End-to-end tests | Selenium 4.25 + WebDriverManager | 5 critical user journeys |
| CI/CD | Jenkins (declarative `Jenkinsfile`) + a GitHub Actions mirror | Jenkins is the pipeline of record |
| Containers | Docker multi-stage; Compose | 401 MB image, JRE-only runtime |
| Provisioning | Ansible (roles, playbooks, rollback) | Idempotent, auditable, reversible |

---

## API catalogue

Full request/response shapes and examples: `docs/05-api-catalogue.md`.
Interactive: `http://localhost:8080/swagger-ui.html`.

### Events

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/events` | Ingest one event (`201`, `Location`, `409` on duplicate) |
| `POST` | `/api/v1/events/bulk` | Ingest a batch, per-item outcome |
| `GET` | `/api/v1/events` | Search: free text `q` + facets + paging + sort |
| `GET` | `/api/v1/events/export` | CSV of the current filter (capped at 5000 rows) |
| `GET` | `/api/v1/events/{eventId}` | **Drill-down**: event + timeline + related alerts |
| `PATCH` | `/api/v1/events/{eventId}/status` | Operator status transition (audited) |
| `POST` | `/api/v1/events/{eventId}/reprocess` | Re-run the pipeline for one event |
| `GET` | `/api/v1/events/stats/latest` | Newest event's business key (ingestion liveness) |

### Metrics, alerts, feedback, health

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/metrics/summary` | KPI summary: totals, rates, by status/source/surface |
| `GET` | `/api/v1/metrics/timeseries` | Throughput / latency buckets (`hour` or `day`) |
| `GET` | `/api/v1/alerts` | Exception queue: filter by status, severity, code |
| `POST` | `/api/v1/alerts/{alertRef}/acknowledge` | Claim an exception |
| `POST` | `/api/v1/alerts/{alertRef}/resolve` | Close an exception with a resolution note |
| `POST` | `/api/v1/feedback` | Capture explicit user feedback (1–5 + reason) |
| `GET` | `/api/v1/feedback` | Feedback list, filterable by item |
| `GET` | `/api/v1/health` | Readiness (see above) |
| `GET` | `/api/v1/health/slo` | Pipeline SLO gate |
| `POST` | `/api/v1/dev/simulate` | Generate synthetic traffic (dev/test only) |

### Error contract

Every error, from validation to the catch-all, returns the same body:

```json
{
  "timestamp": "2026-10-18T11:56:51.628Z",
  "status": 404,
  "error": "Not Found",
  "message": "No endpoint matches GET /api/v1/nope",
  "path": "/api/v1/nope",
  "fieldErrors": []
}
```

Status codes are used precisely: `400` malformed, `404` unknown path/resource,
`405` wrong verb, `409` duplicate or illegal transition, `503` not ready.

---

## Configuration

Everything is overridable by environment variable, so the same image runs in
every environment. The names follow Spring Boot's relaxed binding for the
`crfd.*` properties.

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `CRFD_ENVIRONMENT` | `local` | Shown on the dashboard env badge |
| `CRFD_SIMULATION_ENABLED` | `true` | Synthetic background traffic |
| `CRFD_SEED_ON_STARTUP` | `true` | Seed 180 backdated events at boot |
| `CRFD_METRICS_WINDOW_HOURS` | `24` | Window for the summary indicators |
| `CRFD_LOW_CONFIDENCE` | `0.30` | Confidence floor that raises an alert |
| `CRFD_HIGH_LATENCY_MS` | `500` | Latency objective |
| `CRFD_CRITICAL_LATENCY_MS` | `1500` | Latency that is a critical exception |
| `CRFD_ASYNC_ENABLED` | `true` | `false` makes the pipeline synchronous (used by tests) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | H2 file | Point at MySQL for the `mysql` profile |

Profiles:

```bash
java -jar app.jar                          # default: H2 file, simulation on
java -jar app.jar --spring.profiles.active=prod    # no simulation, no seeding
java -jar app.jar --spring.profiles.active=mysql   # MySQL datasource
mvn -P war clean package                   # WAR for an external Tomcat
```

---

## Testing

Three layers, each catching a different class of defect.

| Layer | Count | Command | What it proves |
| --- | --- | --- | --- |
| Unit | 12 | `mvn -B test` | Service logic: idempotency, thresholds, alert lifecycle, timeline ordering |
| API integration | 20 | `mvn -B test` | Every MVP capability end-to-end over the real context and a real H2 database |
| End-to-end (Selenium) | 5 | `mvn -B -P selenium verify` | The five critical user journeys through a real browser |

```bash
mvn -B clean verify                 # unit + integration, then package
mvn -B -P selenium verify           # add the Selenium journeys
mvn -B -P selenium verify -Dselenium.baseUrl=http://localhost:18080
```

Failing Selenium journeys automatically write a **PNG screenshot**, the **page
source** and a **diagnostic text file** into `target/selenium-screenshots/`,
which the pipeline archives as failure evidence.

---

## Docker

`Dockerfile` is a multi-stage build: Maven + JDK 21 compiles and packages,
then only a JRE 21 (Alpine, non-root user) ships. The dependency layer is
copied before the sources so source-only edits do not re-download the world.

```bash
docker build -t crfd:1.0.0 -t crfd:latest .      # build
docker run -d --name crfd-app -p 8080:8080 \
  -e CRFD_ENVIRONMENT=docker \
  -v crfd-data:/app/crfd-data crfd:1.0.0          # run
docker logs -f crfd-app                           # logs
docker stop crfd-app && docker start crfd-app     # stop / start
docker restart crfd-app                           # restart
docker rm -f crfd-app                             # remove
```

`scripts/docker-lifecycle.sh` (and the `.ps1` twin) runs the whole lifecycle in
order and captures the evidence:

```bash
scripts/docker-lifecycle.sh all        # build → tag → run → health → restart → stop/start
scripts/docker-lifecycle.sh logs
scripts/docker-lifecycle.sh clean
```

Event data lives on the `crfd-data` volume, so stopping, removing and recreating
the container never loses data — which is what makes "deploy a fresh container"
safe.

---

## Jenkins (CI/CD)

`Jenkinsfile` is a declarative pipeline with a **parameterised environment
setting** and ten stages:

```
1  Checkout & metadata          git sha, branch, version, build-info.properties
2  Build & unit test            mvn -B clean verify   (+ JaCoCo)
3  Package                      archive the JAR + checksums
4  Publish test reports         JUnit XML + coverage HTML
5  Selenium E2E quality gate    real browser vs. real container — failure STOPS the pipeline
6  Docker build & tag           versioned tag + git sha + latest
7  Publish image                Docker Hub / local registry (optional)
8  Deploy                       fresh container replaces the old one
9  Health check & smoke test    /api/v1/health, then one ingest-and-read round trip
10 Provision (Ansible)          optional idempotent node provisioning
```

Parameters include `ENVIRONMENT` (dev/staging/production), `APP_PORT`,
`IMAGE_TAG`, `REGISTRY`, `RUN_SELENIUM`, `DEPLOY`, `RUN_ANSIBLE` and the
threshold overrides `CRFD_LOW_CONFIDENCE` / `CRFD_CRITICAL_LATENCY_MS`.

The controller itself is provisioned as code — no hand-clicked setup:

```bash
docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d
# → http://localhost:8085    (admin / crfd-admin, JCasC-configured)
```

`infra/jenkins/jenkins.yaml` (Configuration as Code) declares the tools, the
credentials and a seed job that builds the pipeline straight from the
`Jenkinsfile` in the repository.

Configuration and job setup walkthrough: `docs/08-jenkins-ci-job.md`.

---

## Selenium (end-to-end)

`DashboardJourneyIT` covers the five critical journeys:

| ID | Journey |
| --- | --- |
| J1 | Dashboard loads and renders the summary indicators, charts and event table |
| J2 | A submitted feedback event is accepted and becomes searchable |
| J3 | Free-text search combined with the status facet returns only matching events |
| J4 | Drilling into an event shows its state, audit timeline and related alerts |
| J5 | The exception view lists open alerts and an operator can acknowledge one |

Two execution modes, chosen by environment so the same suite runs locally and in CI:

```bash
# local browser
mvn -B -P selenium verify -Dselenium.baseUrl=http://localhost:18080

# Selenium container driving the app container (what Jenkins does)
scripts/run-selenium.sh remote
```

`SELENIUM_REMOTE_URL` switches to a `selenium/standalone-chrome` container.
Because the browser runs inside that container, `selenium.baseUrl` must be the
**in-network** address (`http://crfd-app:8080`), not `localhost`.

---

## Provisioning with Ansible

```
infra/ansible/
├── ansible.cfg
├── Dockerfile.controller        pinned Ansible controller
├── entrypoint.controller.sh     stages the mount into a non-world-writable copy
├── node/                        disposable "clean node" (SSH + Docker, nothing else)
├── inventory/                   hosts, group_vars, demo-overrides.yml
├── roles/
│   ├── common/                  baseline: timezone, deploy user, directories
│   ├── docker/                  container host; detects and no-ops if already present
│   ├── crfd_app/                deploy the release, record the rollback point, verify health
│   └── nginx/                   edge reverse proxy
└── playbooks/
    ├── site.yml                 the whole lifecycle
    ├── provision.yml            bare node → container host
    ├── deploy.yml               rolling release (serial: 1)
    ├── healthcheck.yml          read-only verification
    └── rollback.yml             recovery to the recorded previous release
```

```bash
# reproduce the full evidence trail on one machine
scripts/ansible-demo.sh

# or against real hosts
ansible-playbook -i inventory/hosts.example.ini playbooks/site.yml
ansible-playbook -i inventory/hosts.example.ini playbooks/site.yml   # again: changed=0
ansible-playbook -i inventory/hosts.example.ini playbooks/rollback.yml
```

**Idempotency** is the point of the second run: the play recap reports
`changed=0`. **Rollback** works because the `crfd_app` role records the outgoing
release only when the version actually changes, so recovery is symmetric — after
a rollback the recorded point names the release you just left, and you can roll
forward again.

The disposable demo node runs its Docker engine **nested inside another
container**, and a nested engine cannot apply cgroup controller configuration.
`inventory/demo-overrides.yml` therefore blanks the CPU and memory limits (an
empty value omits the parameter) and turns Nginx off — the node is a container
with no init system to supervise it. Everything else is the production code path.

---

## Database

`db/schema.sql` is the reviewable reference DDL — the same schema Hibernate
creates at boot, with the index rationale written down. `db/data.sql` seeds a
small, deterministic demo set and is idempotent (it clears and re-inserts only
rows carrying the `-SEED-` marker).

```bash
# MySQL profile
docker compose -f docker-compose.yml -f docker-compose.mysql.yml up -d
```

Four tables: `recommendation_event` (the wide, write-hot event stream),
`event_audit` (append-only status timeline), `alert` (the exception queue) and
`recommendation_feedback` (explicit user feedback).

---

## Repository layout

```
content-recommendation-feedback-dashboard/
├── pom.xml                       build; profiles: selenium, war, mysql
├── Dockerfile                    multi-stage image
├── docker-compose.yml            app + nginx + volume
├── docker-compose.mysql.yml      MySQL shape
├── Jenkinsfile                   the CI/CD pipeline of record
├── mvnw / mvnw.cmd / .mvn/       pinned Maven wrapper
├── src/main/java/…               application
├── src/main/resources/static/    dashboard UI (no bundler)
├── src/test/java/…               unit, API and Selenium tests
├── db/                           schema.sql, data.sql
├── infra/
│   ├── jenkins/                  controller compose + JCasC + plugins
│   ├── nginx/                    reverse proxy config
│   ├── selenium/                 compose overlay for the browser node
│   └── ansible/                  controller, roles, playbooks, inventory
├── scripts/                      lifecycle, health, smoke, demo (bash + PowerShell)
├── docs/                         the written report (00–18) and evidence
└── .github/                      issue templates, PR template, Actions mirror
```

---

## Git workflow

- **Branches** — `main` (release baseline), `develop` (integration), and
  `type/CRFD-<issue>-<description>` feature branches
  (`feature/…`, `bugfix/…`, `hotfix/…`, `docs/…`, `chore/…`). Full rules: `BRANCHING.md`.
- **Commits** — Conventional Commits, one logical change each, referencing the
  issue: `feat(api): CRFD-101 accept and process feedback events`.
- **Pull requests** — every branch merges through a PR using
  `.github/PULL_REQUEST_TEMPLATE.md`, with a review and a green quality gate.
  A deliberately created and resolved merge conflict is part of the evidence.
- **Releases** — annotated tags `v0.1.0-mvp` and `v1.0.0`, each with release notes.

---

## Documentation index

| Doc | Contents |
| --- | --- |
| `docs/00-project-overview.md` | The brief, the deliverables, how to read this pack |
| `docs/01-problem-definition-and-scope.md` | Problem, users, frozen MVP scope, out of scope |
| `docs/02-agile-plan.md` | Backlog, sprints, estimates, Definition of Done, DevOps lifecycle |
| `docs/03-srs-and-architecture.md` | Requirements, architecture, components, decisions |
| `docs/04-data-model.md` | Entities, relationships, indexing |
| `docs/05-api-catalogue.md` | Every endpoint with examples |
| `docs/06-git-and-github-workflow.md` | Branching, PRs, reviews, commit history |
| `docs/07-mvp-and-merge-conflict.md` | MVP completion, the conflict and its resolution, tags |
| `docs/08-jenkins-ci-job.md` | Jenkins install, configuration, the CI job |
| `docs/09-pipeline-and-deployment.md` | Pipeline stages, Tomcat/Nginx deployment |
| `docs/10-selenium-test-plan.md` | Test plan, journeys, locator strategy |
| `docs/11-continuous-testing-and-defect-fix.md` | Continuous testing, the seeded defect and its fix |
| `docs/12-docker-lifecycle.md` | Image, lifecycle, evidence |
| `docs/13-jenkins-docker-cd.md` | Continuous deployment through Jenkins and Docker |
| `docs/14-ansible-provisioning.md` | Clean node, idempotency, health check, rollback |
| `docs/15-final-end-to-end-and-viva.md` | End-to-end walkthrough and viva questions |
| `docs/16-limitations-and-future-enhancements.md` | Honest limitations and future work |
| `docs/17-troubleshooting-guide.md` | Symptoms → cause → fix |
| `docs/18-local-runbook.md` | Step-by-step local setup |

`docs/evidence/` holds the captured run logs, screenshots and test output.

---

## Known limitations

Stated plainly, because a report that claims none is not credible. The full
discussion is in `docs/16-limitations-and-future-enhancements.md`.

- **H2 file mode is single-writer.** Fine for the MVP and for demos; MySQL is
  the staging/production shape, and a connection-pool/cluster story is future work.
- **The dashboard table search is a substring match**, not full text. It is
  correct but will not scale to tens of millions of rows without a trigram or
  search index.
- **Serving latency is simulated**, so the latency indicators exercise the
  pipeline rather than a real model server.
- **Authentication is out of MVP scope.** The API is open on a trusted network;
  the Alerts "owner" field is free text rather than a real identity.
- **Alert ingestion is polled by a scheduler**, not pushed; the annotation-based
  async path is bounded by the pipeline's own transaction boundaries.
- **Selenium covers five journeys**, not the whole UI surface.

---

## Licence

MIT — see `LICENSE`.
