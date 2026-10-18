# 03 — Software requirements specification and architecture

## 1. Scope

This document specifies what the CRFD MVP must do, what qualities it must have,
and the design that satisfies both. It is the technical counterpart to the
product scope in `docs/01-problem-definition-and-scope.md`.

## 2. Functional requirements

Traceable to the six frozen capabilities. Each requirement is testable, and the
test that covers it is named so that the requirement is not merely asserted.

### FR-1 — Data and event entry (CRFD-101)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-1.1 | The system shall accept a recommendation feedback event over HTTP with `userId`, `itemId`, `itemTitle`, `eventType`, `source`, `surface`, `score`, and optional `rating`, `comment`, `clientTimestamp`, `eventId`, `correlationId`. | `ingestEvent` |
| FR-1.2 | The system shall return `201 Created` with a `Location` header identifying the created event. | `ingestEvent` |
| FR-1.3 | If the client does not supply an `eventId`, the system shall mint a human-readable business key of the form `EVT-XXXXXXXXXXXX`. | `ingestEvent`, J2, J4 |
| FR-1.4 | The system shall reject a duplicate `eventId` with `409 Conflict` **and shall record the duplicate attempt as an exception**, so retries are visible rather than silent. | `rejectDuplicateEvent` |
| FR-1.5 | The system shall accept a batch and report a per-item outcome (`accepted` / `rejected` with a reason). | `bulkIngest` |
| FR-1.6 | The system shall reject a malformed payload with `400` and field-level errors. | `rejectInvalidPayload` |
| FR-1.7 | Every valid event shall be classified as `PROCESSED`, `FAILED` or `QUARANTINED`, and each transition shall append an immutable audit entry. | `quarantineEventWithoutItemId`, `manualStatusTransitionIsAudited` |

### FR-2 — Searchable dashboard (CRFD-102)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-2.1 | The system shall provide free-text search across event id, correlation id, user id, item id and item title. | `searchDashboard` |
| FR-2.2 | The system shall support facets for status, source, surface and event type, combinable with free text. | `searchDashboard`, J3 |
| FR-2.3 | Results shall be paged and sortable, with the page size bounded (≤ 200) so a single request cannot exhaust the server. | `searchDashboard` |
| FR-2.4 | The current filter shall be exportable as CSV, capped at 5000 rows. | `csvExport` |
| FR-2.5 | The dashboard shall render the table, filters, paging and export without a page reload. | J1, J2, J3 |

### FR-3 — Summary indicators (CRFD-103)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-3.1 | The system shall report total, processed, failed and quarantined event counts over a configurable window. | `summaryIndicators` |
| FR-3.2 | The system shall report the pipeline success rate and the p95 serving latency. | `summaryIndicators` |
| FR-3.3 | The system shall report click-through, dismiss and average-rating indicators. | `summaryIndicators` |
| FR-3.4 | The system shall break events down by status, source and surface. | `summaryIndicators`, J1 |
| FR-3.5 | The system shall report throughput over time with hourly and daily granularity. | `timeseries` |
| FR-3.6 | Indicators shall be computed with aggregate queries, not by loading rows into memory. | design; `summaryIndicators` |

### FR-4 — Status drill-down (CRFD-104)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-4.1 | The system shall return one event with all of its fields. | `drillDown` |
| FR-4.2 | The system shall return the event's complete status timeline in a deterministic order. | `drillDown` |
| FR-4.3 | The system shall return the exceptions raised against that event. | `drillDown`, J4 |
| FR-4.4 | An operator shall be able to set an event's status manually; the change shall be audited with actor and note. | `manualStatusTransition` |
| FR-4.5 | The system shall be able to re-run the pipeline for a single event. | `drillDown` |
| FR-4.6 | An unknown `eventId` shall return `404` with the uniform error body. | `unknownEventReturns404` |

### FR-5 — Alert / exception view (CRFD-105)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-5.1 | The system shall raise an exception automatically when: a score is below the confidence floor; serving latency exceeds the objective; an event is older than the staleness budget; the dismiss rate exceeds its threshold; a rating falls below the floor; a duplicate is replayed; a payload cannot be interpreted. | `flagsStaleEvent`, `raiseCreatesOpenAlert`, `rejectDuplicateEvent` |
| FR-5.2 | A repeated breach of the same code on the same event shall **not** open a second alert. | `raiseSuppressesDuplicateOpenAlert` |
| FR-5.3 | Alerts shall be filterable by status, severity and code. | `alertLifecycle` |
| FR-5.4 | An operator shall be able to acknowledge and resolve an alert, each recorded with timestamp, owner and note. | `alertLifecycle`, `acknowledgeTransitions`, `resolveTransitions` |
| FR-5.5 | An alert that has been resolved shall not be acknowledgeable. | `cannotAcknowledgeResolved` |
| FR-5.6 | Every alert shall carry a stable human-readable reference `ALT-XXXXXXXX`. | `raiseCreatesOpenAlert` |
| FR-5.7 | The exception view shall let an operator acknowledge an alert from the UI. | J5 |

### FR-6 — Explicit feedback (CRFD-106)

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-6.1 | The system shall accept an explicit rating (1–5) with an optional reason and comment. | `explicitFeedback` |
| FR-6.2 | A rating below the configured floor shall raise a `RATING_DROP` alert. | `explicitFeedback` |
| FR-6.3 | A rating outside 1–5 shall be rejected with `400`. | `explicitFeedback` |
| FR-6.4 | Feedback shall be listable and filterable by item. | `explicitFeedback` |

### FR-7 — Operational and deployment support

| ID | Requirement | Verified by |
| --- | --- | --- |
| FR-7.1 | The system shall expose a readiness endpoint that returns `200` while the instance can serve traffic, and `503` only when a hard dependency is down. | `deepHealth`, `readinessIsIndependentOfTheSloGate` |
| FR-7.2 | The system shall expose a separate SLO gate that returns `503` while the pipeline success-rate objective is breached. | `sloGatePassesWhenWithinObjective` |
| FR-7.3 | The system shall expose liveness through Spring Boot Actuator for container orchestration. | `deepHealth` |
| FR-7.4 | The system shall return `404` (not `500`) for an unmapped URL and `405` for a wrong verb on a real path. | `unmappedUrlReturnsNotFoundInsteadOfServerError`, `wrongVerbReturnsMethodNotAllowed` |
| FR-7.5 | The system shall expose an OpenAPI description and Swagger UI. | manual |
| FR-7.6 | Synthetic traffic and start-up seeding shall be switchable by configuration, and **off by default in production**. | `application-prod.yml` |

## 3. Non-functional requirements

| ID | Quality | Requirement | How it is met / evidenced |
| --- | --- | --- | --- |
| NFR-1 | **Performance** | Read endpoints shall answer in well under a second at MVP volume; ingestion shall not block on pipeline processing. | Indexed aggregate queries; single-row writes; simulation of pipeline latency off the request thread. Aggregation is done in SQL. |
| NFR-2 | **Scalability** | The API shall be stateless so more instances can be added behind the proxy. | No server-side session state; the only state is the database. `open-in-view: false`. |
| NFR-3 | **Reliability** | A deployment must never lose event data. | Events live on a named Docker volume; the container can be removed and recreated with no data loss (proved in `docs/12`). |
| NFR-4 | **Idempotency** | Repeated provisioning and repeated ingestion shall both be safe. | Ingestion: unique business key. Provisioning: `changed=0` on the second `site.yml` run (`docs/14`). |
| NFR-5 | **Recoverability** | A bad release shall be reversible without editing anything by hand. | `playbooks/rollback.yml` returns to the recorded previous release and re-verifies health. |
| NFR-6 | **Observability** | Health, key metrics and exceptions shall be visible without attaching a debugger. | `/api/v1/health`, `/api/v1/health/slo`, Actuator, the exception queue, and log lines with a correlation id per request. |
| NFR-7 | **Security** | No secrets in the repository; the container shall not run as root; error responses shall not leak stack traces. | Placeholder credentials in JCasC; non-root `crfd` user in the image; `GlobalExceptionHandler` returns a correlation id instead of an exception. |
| NFR-8 | **Portability** | The same artefact shall run as a JAR or a WAR, and on H2 or MySQL, without code changes. | Maven profiles; environment-variable configuration. |
| NFR-9 | **Maintainability** | The code shall be readable without generated boilerplate. | Records for DTOs, hand-written entities, no Lombok, package-by-layer with a clear service boundary. |
| NFR-10 | **Testability** | Every capability shall be verifiable without a browser, and the critical journeys with one. | 31 unit/integration tests + 5 Selenium journeys. |
| NFR-11 | **Build reproducibility** | The build shall not depend on what happens to be installed. | Maven wrapper pinned at 3.9.16; Dockerised Jenkins; pinned Ansible controller. |
| NFR-12 | **Usability** | The dashboard shall be usable at 1366×768 and readable without colour alone. | Responsive layout; status is conveyed by text as well as colour. |

## 4. Architecture

### 4.1 Component view

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                        CRFD application  (single artefact)                    │
│                                                                               │
│  ┌──────────────────────── web layer  (com.crfd.dashboard.web) ────────────┐  │
│  │  EventController   MetricsController   AlertController                    │  │
│  │  FeedbackController  HealthController  SimulationController               │  │
│  │  GlobalExceptionHandler  →  uniform ApiError contract                     │  │
│  └───────────────────────────────┬──────────────────────────────────────────┘  │
│                                  │ DTOs (Java records)                         │
│  ┌───────────────────────────────▼──── service layer ───────────────────────┐  │
│  │  EventService           ingest / search / export / detail / transition    │  │
│  │  EventProcessingService the pipeline: score, transition, audit            │  │
│  │  PipelineScheduler      dispatches processing (own transaction)           │  │
│  │  AlertService           raise / suppress / acknowledge / resolve          │  │
│  │  MetricsService         summary indicators, time series                   │  │
│  │  FeedbackService        explicit feedback                                 │  │
│  │  HealthService          readiness + SLO evaluation                        │  │
│  │  TrafficSimulator, DataSeeder   (config-gated, off in production)         │  │
│  └───────────────────────────────┬──────────────────────────────────────────┘  │
│                                  │ Spring Data JPA                              │
│  ┌───────────────────────────────▼──── persistence layer ───────────────────┐  │
│  │  RecommendationEventRepository  AlertRepository                           │  │
│  │  AuditEntryRepository           FeedbackRepository                        │  │
│  └───────────────────────────────┬──────────────────────────────────────────┘  │
│                                  │ JDBC (HikariCP)                             │
│  ┌───────────────────────────────▼──────────────────────────────────────────┐  │
│  │  H2 file mode (default)   |   MySQL 8 (mysql profile)                     │  │
│  └──────────────────────────────────────────────────────────────────────────┘  │
│                                                                               │
│  static/  index.html · styles.css · app.js · favicon.svg   (no bundler)       │
└───────────────────────────────────────────────────────────────────────────────┘
```

**Why a layered monolith and not services.** The MVP is one bounded context with
one database and one deployment. Splitting it would add network failure modes,
distributed transactions and an operational burden in exchange for nothing the
product needs. The layering is strict — controllers never touch repositories, and
services never return entities to the web layer — so the seams for a future split
are already in place.

### 4.2 Runtime view — ingestion

```
 surface            EventController        EventService        PipelineScheduler
    │                     │                     │                     │
    │ POST /api/v1/events │                     │                     │
    ├────────────────────▶│                     │                     │
    │                     │ validate (jakarta)  │                     │
    │                     ├────────────────────▶│                     │
    │                     │                     │ mint EVT-key if absent
    │                     │                     │ existsByEventId? ──▶ 409
    │                     │                     │                     │
    │                     │                     │ persist RECEIVED    │
    │                     │                     │ + audit(RECEIVED)   │
    │                     │                     ├────────────────────▶│
    │                     │                     │                     │ schedule
    │ 201 + Location      │◀────────────────────┤                     │
    │◀────────────────────┤                     │                     │
    │                     │                     │      (after commit) │
    │                     │                     │                     ▼
    │                     │            EventProcessingService  @Transactional(REQUIRES_NEW)
    │                     │                     │  simulate scoring latency
    │                     │                     │  PROCESSED | FAILED | QUARANTINED
    │                     │                     │  + audit(transition)
    │                     │                     │  evaluate thresholds ──▶ AlertService.raise
    │                     │                     │                            (suppress duplicates)
```

The `REQUIRES_NEW` boundary is the non-obvious part. Processing is dispatched from
a commit hook so the event is visible to the pipeline, but the hook runs *after*
the original transaction has committed and the persistence context is closed.
Without its own transaction, the pipeline's writes are silently discarded. This
was a real defect found during sprint 1 (see `docs/11`).

### 4.3 Deployment view — three shapes

| Shape | Artefact | Container | Proxy | Command |
| --- | --- | --- | --- | --- |
| **A. Embedded Tomcat (default)** | executable fat JAR | embedded | none | `java -jar app.jar` |
| **B. Docker + Nginx** | image from the same JAR | Docker, non-root | Nginx container | `docker compose up -d --build` |
| **C. External Tomcat** | WAR | external Tomcat 10.1 | optional | `mvn -P war clean package` → drop in `webapps/` |

Shape A is what developers run. Shape B is what the pipeline deploys and what the
Ansible playbooks provision. Shape C exists to satisfy the brief's "Tomcat
deployment target" requirement explicitly and to prove the app is not coupled to
its embedded container. All three run the same code and the same endpoints.

In shape B, Nginx is in front for three reasons: it terminates the client
connection so the app container can be replaced without the entry point changing,
it exposes `/healthz` for deployment tooling, and it applies rate limiting to the
ingest path — concerns that do not belong in the application.

### 4.4 Key design decisions

| # | Decision | Alternatives rejected | Why |
| --- | --- | --- | --- |
| D1 | **Java 21 + Spring Boot 3.3.5** | Node/Express, Python/FastAPI | Spring Data JPA gives the aggregate projections and the specification-based search for free; Java 21 records remove the DTO boilerplate that usually justifies Lombok; the team's existing skill is Java. |
| D2 | **Maven, with the wrapper committed** | Gradle, Ant | Maven profiles express "JAR or WAR or MySQL" declaratively, which is exactly the shape of the requirement. Ant would mean hand-writing the lifecycle. The wrapper pins 3.9.16 so CI, Docker and laptops agree. |
| D3 | **H2 file mode by default; MySQL profile** | MySQL everywhere; H2 in-memory | A reviewer must be able to run the product with zero installation. File mode survives a restart, so the drill-down still has data. MySQL is a profile, not a rewrite. |
| D4 | **Fat JAR + Nginx (embedded Tomcat), plus a WAR profile** | WAR-only; external container always | One artefact is the cheapest thing to build, ship and health-check. The WAR profile proves portability without making it the default. |
| D5 | **Vanilla ES2020 + CSS, no bundler** | React + Vite | A bundler adds a Node toolchain, a second build step, a `node_modules` in CI and a hash-manifest to the deploy. The dashboard is one page with five views; the browser is perfectly capable of rendering it directly, and the artefact stays one JAR. |
| D6 | **One wide event table** | Normalised payload table; event-sourcing | Writes are the hot path and the dashboard is one indexed search over the same rows. Normalisation would add a join to every read to save space the MVP does not need. Recorded as future work. |
| D7 | **Readiness and SLO as separate endpoints** | One endpoint that is `503` whenever anything is degraded | Conflating them means a business-metric dip takes a healthy node out of rotation and fails a good deployment. Readiness answers "can you serve?", the SLO gate answers "are you meeting the objective?" — different consumers, different actions. |
| D8 | **Simulated serving latency** | A real model server | The thing under test is the feedback pipeline. Simulating latency exercises the latency thresholds, the spike path and the p95 indicator deterministically. |
| D9 | **Annotations for scheduled/simulated work, explicit `REQUIRES_NEW` for the pipeline** | All-async; all-synchronous | The pipeline must run in its own transaction to survive dispatch from a commit hook, but must also be runnable synchronously (`crfd.processing.async-enabled=false`) so tests are deterministic rather than timing-dependent. |
| D10 | **Jenkins configuration as code** | Click through the Jenkins UI | A hand-configured controller cannot be reviewed, reproduced or rebuilt. Compose + JCasC + `plugins.txt` + a seed job makes the controller part of the repository. |

### 4.5 Configuration model

Every operational value is an environment variable with a working default, so one
image runs everywhere and the pipeline can parameterise a build without editing
anything:

```yaml
crfd:
  environment:  ${CRFD_ENVIRONMENT:local}
  simulation:
    enabled:         ${CRFD_SIMULATION_ENABLED:true}
    seed-on-startup: ${CRFD_SEED_ON_STARTUP:true}
    seed-event-count:${CRFD_SEED_EVENT_COUNT:180}
  processing:
    async-enabled:   ${CRFD_ASYNC_ENABLED:true}      # false → deterministic tests
  thresholds:
    low-confidence:      ${CRFD_LOW_CONFIDENCE:0.30}
    high-latency-ms:     ${CRFD_HIGH_LATENCY_MS:500}
    critical-latency-ms: ${CRFD_CRITICAL_LATENCY_MS:1500}
    minimum-success-rate:${CRFD_MINIMUM_SUCCESS_RATE:0.95}
  metrics:
    window-hours:        ${CRFD_METRICS_WINDOW_HOURS:24}
```

Thresholds are configuration rather than constants precisely because the brief
asks for a parameterised environment setting: the pipeline can deploy the same
build with a stricter confidence floor in staging and prove that the alerts fire.

### 4.6 Error model

One shape for every error, produced by `GlobalExceptionHandler`:

| Status | Trigger | Handler |
| --- | --- | --- |
| `400` | Bean-validation failure, unreadable body, type mismatch | `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` |
| `404` | Unknown resource, or a path that matches nothing | `NotFoundException`, `NoResourceFoundException` |
| `405` | A real path, wrong verb (e.g. `GET` on the status `PATCH`) | `HttpRequestMethodNotSupportedException` |
| `409` | Duplicate business key, illegal status transition | `DuplicateEventException`, `IllegalStateException` |
| `503` | Readiness: a hard dependency is down. SLO gate: objective breached | `HealthController` |
| `500` | Genuinely unexpected | catch-all, logs the stack trace, returns a correlation id |

The contract exists so a client can distinguish "retry later" (`503`) from "this
will never work" (`404`, `405`) from "fix your request" (`400`, `409`). Turning
client errors into `500` was a real defect, fixed as CRFD-121 (`docs/11`).

## 5. Traceability

| Capability | Requirements | API | Tests | Journey |
| --- | --- | --- | --- | --- |
| CRFD-101 | FR-1.* | `/api/v1/events`, `/bulk` | `ingestEvent`, `bulkIngest`, `rejectDuplicateEvent`, `rejectInvalidPayload`, `quarantineEventWithoutItemId`, `noEventTypeProducesServerError` | J2 |
| CRFD-102 | FR-2.* | `/api/v1/events`, `/export` | `searchDashboard`, `csvExport` | J2, J3 |
| CRFD-103 | FR-3.* | `/api/v1/metrics/*` | `summaryIndicators`, `timeseries` | J1 |
| CRFD-104 | FR-4.* | `/api/v1/events/{id}`, `/{id}/status`, `/{id}/reprocess` | `drillDown`, `manualStatusTransition`, `manualStatusTransitionIsAudited`, `unknownEventReturns404` | J4 |
| CRFD-105 | FR-5.* | `/api/v1/alerts*` | `raiseCreatesOpenAlert`, `raiseSuppressesDuplicateOpenAlert`, `flagsStaleEvent`, `alertLifecycle`, `acknowledgeTransitions`, `resolveTransitions`, `cannotAcknowledgeResolved`, `unknownAlert` | J5 |
| CRFD-106 | FR-6.* | `/api/v1/feedback` | `explicitFeedback` | — |
| Operational | FR-7.* | `/api/v1/health`, `/health/slo` | `deepHealth`, `readinessIsIndependentOfTheSloGate`, `sloGatePassesWhenWithinObjective`, `unmappedUrlReturnsNotFoundInsteadOfServerError`, `wrongVerbReturnsMethodNotAllowed` | — |
