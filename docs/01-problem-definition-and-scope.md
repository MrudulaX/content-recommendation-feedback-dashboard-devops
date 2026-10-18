# 01 — Problem definition and scope

## The problem

A content platform serves personalised item recommendations. Recommendations are
produced by several sources (collaborative filtering, content-based, trending,
editorial, and a hybrid that blends them) and are shown on several surfaces (home
feed, product page, search results, email, push notification).

The recommenders work. What is missing is **operational and analytical visibility
into what they are doing**. Today, when something is wrong, the symptoms appear
in three disconnected places:

1. **Product sees it, engineering does not.** A surface starts recommending
   poorly-rated or already-seen items; the first signal is a support ticket.
2. **Nobody can answer "what happened to that recommendation?"** There is no way
   to trace one recommendation from serve time through to the user's reaction, so
   every investigation starts by guessing which log file to grep.
3. **Failures are invisible.** A batch of recommendations fails to score because
   the model server timed out, and nothing raises an exception. The success rate
   quietly drops and is only noticed at the end of the month.

The consequence is a mean time to detect measured in days, a mean time to
diagnose measured in hours, and a pipeline whose quality is argued about rather
than measured.

## Who the users are

| User | What they need | Success looks like |
| --- | --- | --- |
| **Content platform engineer** (primary) | To see pipeline health at a glance and trace a single recommendation end to end | Spots a latency regression in under a minute; resolves a trace in under five minutes |
| **Site reliability engineer** (primary) | An exception queue with severity and ownership, and a machine-readable health signal | Every unresolved exception has an owner; deployment tooling can gate on health |
| **Data / quality analyst** (secondary) | Summary indicators and a searchable, exportable event set | Can pull the last 24 hours of low-confidence recommendations into a spreadsheet without asking an engineer |
| **Recommendation scientist** (secondary) | Per-source and per-surface breakdowns of success and explicit feedback | Can tell whether a model change helped, on evidence |

## Why a dashboard and not just metrics

Metric dashboards answer *"is the aggregate healthy?"*. They do not answer
*"what happened to recommendation ID 7f3a?"*, which is the question an engineer
actually has during an incident. This product deliberately covers **both** read
models over the same data:

- **aggregate** — KPIs, time series, breakdowns (the *"is it healthy?"* question);
- **per-event** — search, drill-down, timeline, related exceptions (the *"what
  happened to this one?"* question).

Serving both from one event stream is what makes the drill-down trustworthy: the
count on the KPI strip and the row in the table are literally the same rows.

## MVP scope — frozen

The scope was fixed at the start of the project and has not moved. Six
capabilities, and nothing else:

| ID | Capability | User story (short form) | Measurable acceptance |
| --- | --- | --- | --- |
| **CRFD-101** | Data / event entry | *As a surface, I want to publish recommendation feedback events so that the pipeline has something to reason about.* | `POST /api/v1/events` returns `201` + `Location`; duplicate `eventId` returns `409` and raises an exception; bulk endpoint reports per-item outcomes |
| **CRFD-102** | Searchable dashboard | *As an engineer, I want to search and filter events so that I can find the one I care about.* | Free-text `q`, facets (status, source, surface, type, item, user), paging, sorting, CSV export of the current filter |
| **CRFD-103** | Summary indicators | *As an analyst, I want the pipeline's vital signs so that I know whether to dig.* | Totals, processed count, success rate, p95 latency, click-through and dismiss rates, time series, breakdowns by status/source/surface |
| **CRFD-104** | Status drill-down | *As an engineer, I want to see what happened to one recommendation.* | Event detail + immutable audit timeline + related exceptions + audited manual status transition + reprocess |
| **CRFD-105** | Alert / exception view | *As an SRE, I want a work queue of exceptions so that nothing is silently ignored.* | Threshold breaches raise alerts automatically; filter by status/severity/code; acknowledge and resolve with an audit trail |
| **CRFD-106** | Explicit feedback | *As a scientist, I want explicit user ratings so that quality is not inferred from behaviour alone.* | `POST /api/v1/feedback` with a 1–5 rating and a reason; low ratings raise `RATING_DROP`; list filterable by item |

## Out of scope — deliberately excluded

Naming these explicitly is part of the scope definition. Each one was considered
and deferred, with the reason recorded:

| Excluded | Why | Where it would go |
| --- | --- | --- |
| Authentication and authorisation | The MVP is deployed on a trusted internal network; adding auth would consume sprint capacity that the six capabilities need. The `owner` field is free text rather than a real identity. | Sprint 6+ (see `docs/16`) |
| A real model server | The value under test is the *feedback* pipeline, not the model. Serving latency is simulated so the latency indicators and thresholds are genuinely exercised. | Out of the 15 weeks |
| Multi-tenancy | No requirement exists yet; it would change every table and every query. | Post-MVP |
| Streaming ingestion (Kafka et al.) | An HTTP ingest endpoint proves the same pipeline semantics with far less operational surface, and keeps the whole MVP runnable from one JAR. | Post-MVP |
| Full-text search engine | The MVP's search is a substring match over indexed columns, which is correct and adequate at this data volume. Replacing it is a scaling decision, not a correctness one. | When the table outgrows it |
| Mobile client | The dashboard is used on desktop; a responsive layout is enough. | Not planned |
| Alerting by email/Slack/PagerDuty | Alerts are surfaced in the dashboard and over the API; the *delivery channel* is an integration, not a capability. | Post-MVP |

## Constraints

| Constraint | Consequence |
| --- | --- |
| 15 weeks of part-time effort | Sprints are one week and small; the backlog is sized accordingly |
| No budget for cloud infrastructure | Everything runs on one developer machine: Docker Desktop, local Jenkins container, disposable Ansible node |
| One developer | "Code review" is a genuine second pass over the diff against the PR checklist in `.github/PULL_REQUEST_TEMPLATE.md`, recorded in `docs/06` |
| Must demonstrate *both* embedded-Tomcat (JAR) and external-Tomcat (WAR) deployment | A `war` Maven profile exists alongside the default JAR |
| No secrets in the repository | Credentials are placeholders in JCasC and are supplied at runtime |

## What "done" means

The project is done when all of the following are true, and all of them are:

- the six capabilities work and are covered by automated tests;
- the Selenium journeys pass in the pipeline, and a failure genuinely blocks a
  deployment;
- the image builds from source and the container lifecycle is reproducible;
- the pipeline is defined in a `Jenkinsfile` in the repository, not clicked into
  a controller;
- the provisioning is idempotent and reversible, with evidence;
- the limitations are written down honestly.

The Definition of Done used for every story is in `docs/02-agile-plan.md`.
