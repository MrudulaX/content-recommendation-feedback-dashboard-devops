# Changelog

All notable changes to the Content Recommendation Feedback Dashboard are recorded
here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Backlog grooming for the post-MVP items (see `docs/16-limitations-and-future-enhancements.md`).

## [1.0.0] - 2026-10-18

Release-ready MVP baseline: the whole DevOps lifecycle, from a commit to a
provisioned node.

### Added

- **Event entry** - `POST /api/v1/events` and `POST /api/v1/events/bulk`, plus a
  synthetic traffic generator that is off in the production profile.
- **Searchable dashboard** - `GET /api/v1/events` with free text plus status,
  source, surface, type, user, item, time-window and score facets, bounded paging,
  sorting with a stable tie-break, and CSV export of the current filter.
- **Summary indicators** - `GET /api/v1/metrics/summary` (totals, rates, p95
  latency, breakdowns) and `GET /api/v1/metrics/timeseries`.
- **Status drill-down** - `GET /api/v1/events/{eventId}` with the immutable
  status-transition audit timeline, plus `PATCH .../status` and
  `POST .../reprocess`.
- **Alert / exception view** - `GET /api/v1/alerts` with acknowledge and resolve,
  and automatic raising from threshold breaches.
- **Explicit recommendation feedback** - `POST /api/v1/feedback`,
  `GET /api/v1/feedback`.
- **Readiness and the SLO gate split into two endpoints** - `GET /api/v1/health`
  for "can this instance serve traffic" and `GET /api/v1/health/slo` for "is the
  pipeline meeting its objective". One endpoint answering both questions took
  working nodes out of service.
- **Jenkins declarative pipeline** (`Jenkinsfile`) - checkout, build, package,
  container build, Selenium quality gate, report and image publish, deploy, health
  check, provisioning. Ten stages, ordered so nothing irreversible precedes
  everything verifiable.
- **Jenkins controller as code** - `infra/jenkins/` (JCasC, a pinned plugin list,
  a Job DSL seed job, a local registry).
- **Selenium WebDriver suite** - five critical journeys, failure screenshots,
  stale-tolerant element reads, runnable locally or against a containerised Chrome.
- **Container image** - a multi-stage `Dockerfile` and a Compose stack
  (application + Nginx edge, named data volume).
- **Provisioning** - Ansible roles and playbooks for clean-node provisioning, an
  idempotent deploy, a health check and a symmetric rollback.
- **Reverse proxy** - Nginx configuration with `/healthz` and `/` routing.
- **Documentation** - the report set `docs/00`-`docs/18`, the per-PR review records
  in `docs/pull-requests/`, and the captured evidence in `docs/evidence/`.

### Fixed

- **CRFD-117** - the event write path minted raw UUIDs instead of the
  `EVT-`-prefixed business keys that the API contract, the dashboard, the smoke
  test and the Ansible assertions all refer to. Caught by Selenium journeys J2 and
  J4; the unit and API tests could not see it.
- **CRFD-121** - an unmapped URL returned 500 instead of 404, and a wrong HTTP verb
  returned 500 instead of 405. A client mistake was being reported as a server
  fault, which is also a false alert.
- **CRFD-105** - `GET /api/v1/health` returned 503 when the pipeline success rate
  dipped below its objective, which is a business metric and not an availability
  one. Readiness and the SLO gate are now separate concerns.

### Security

- The synthetic traffic simulator is gated by configuration and disabled by the
  production profile, so it fails closed.
- Jenkins credentials are referenced by id and injected at runtime; no secret is in
  the repository.
- The application image runs as a non-root user.

## [0.1.0-mvp] - 2026-10-18

Feature-complete MVP: all six frozen capabilities implemented and demonstrable.

- Event ingestion with duplicate rejection, quarantine and an audit trail.
- Searchable dashboard with facets, paging, sorting and CSV export.
- Summary indicators and throughput time series.
- Status drill-down with an immutable audit timeline.
- Exception raising and the alert queue.
- Explicit feedback capture.
- Dashboard UI.

Verified at this point: 31 unit and API tests green; five Selenium journeys green
twice, against a local JAR and against a containerised application. The container
image, the Jenkins pipeline, the Selenium quality gate and the provisioning
playbooks followed before 1.0.0.
