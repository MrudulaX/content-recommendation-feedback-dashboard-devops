# 02 — Agile plan

## Approach

Two-week sprints, a one-week inception, and a **single, ordered backlog** in which
the product stories and the toolchain stories are both visible. Keeping them in
one backlog matters: the toolchain is not an afterthought bolted on at the end,
and it is not "infrastructure work" that can silently slip. If the Selenium story
is in the sprint, the sprint is not done until the journeys run in the pipeline.

| | |
| --- | --- |
| Cadence | Two-week sprints, weekly refinement |
| Inception | Week 1 |
| Total | 15 weeks |
| Estimation | Story points, modified Fibonacci (1, 2, 3, 5, 8, 13) |
| Plan of record | This document; the change log is the Git history |
| Board | GitHub issues labelled `user-story`, one issue per story, referenced from every commit |

**Why 15 weeks is enough.** The estimate is deliberately conservative for a
part-time solo effort: 8 sprints × 5 working days × ~4 productive hours = ~160
hours, against a backlog of 89 points. The observed velocity was stable enough
after sprint 2 to stop re-planning the later sprints.

## Estimation rules

- **1 point** — a change to one file with tests, no unknowns.
- **3 points** — one capability slice or one infrastructure step, understood.
- **5 points** — a slice with one genuine unknown, or a slice touching several
  layers (API + service + repository + UI + tests).
- **8 points** — a whole capability with its read model and its tests.
- **13 points** — too big; split it. Nothing in this backlog is a 13.

## Definition of Ready

A story may enter a sprint only when all of these are true:

- [ ] It maps to exactly one frozen MVP capability (`CRFD-101` … `CRFD-106`) or to
      one named toolchain deliverable.
- [ ] The acceptance criteria are written as observable statements, not intent.
- [ ] It fits in one sprint, or has been split.
- [ ] Its dependencies are already merged, or the dependency is inside the same sprint.
- [ ] For a toolchain story: the evidence it will produce is named up front.

## Definition of Done

A story is done only when **every** line is true. This is the same checklist that
appears in `.github/ISSUE_TEMPLATE/user_story.md`, so it is visible where the work
is actually tracked.

- [ ] **Code** — implemented on a `type/CRFD-<issue>-<desc>` branch, merged to
      `develop` through a pull request that used the PR template.
- [ ] **Review** — the diff has been read against the PR reviewer checklist; no
      unresolved comments.
- [ ] **Tests** — acceptance criteria are covered by an automated test, and
      `mvn -B test` is green.
- [ ] **End-to-end** — if the change touches a critical user journey, the relevant
      Selenium journey is updated and `mvn -B -P selenium verify` is green.
- [ ] **Contract** — any API change is reflected in the OpenAPI description and in
      `docs/05-api-catalogue.md`.
- [ ] **Container** — the image still builds and the container still reports healthy.
- [ ] **Docs** — `CHANGELOG.md` updated; affected docs updated.
- [ ] **Evidence** — the artefact that proves it (test summary, screenshot, log,
      pipeline output) is committed under `docs/evidence/` or linked from the PR.
- [ ] **No secrets, no absolute local paths** committed.

## Backlog

Story points are in the `Pts` column. Toolchain stories carry the `[platform]` tag.

### Epic A — Feedback pipeline and data entry

| ID | Story | Pts | Sprint |
| --- | --- | --- | --- |
| CRFD-100 | Inception: freeze scope, write the backlog, agree the DoD, choose the stack | 5 | 0 |
| CRFD-101 | As a surface, I want to publish feedback events so that the pipeline has data | 8 | 1 |
| CRFD-101b | Ingestion must be idempotent: a retried `eventId` is rejected and recorded | 3 | 1 |
| CRFD-101c | Bulk ingestion with per-item outcomes | 3 | 1 |
| CRFD-101d | The pipeline must classify events as PROCESSED / FAILED / QUARANTINED and log every transition | 5 | 1 |
| CRFD-102 | Searchable dashboard (free text + facets + paging + sorting) | 8 | 2 |
| CRFD-102b | CSV export of the current filter | 2 | 2 |
| CRFD-103 | Summary indicators: totals, rates, p95 latency, breakdowns | 8 | 3 |
| CRFD-103b | Throughput time series (hourly / daily) | 3 | 3 |
| CRFD-104 | Status drill-down: detail + immutable audit timeline | 5 | 4 |
| CRFD-104b | Audited manual status transition and reprocess | 3 | 4 |
| CRFD-105 | Automatic exception raising from threshold breaches | 5 | 5 |
| CRFD-105b | Exception queue with filter, acknowledge, resolve | 5 | 5 |
| CRFD-106 | Explicit feedback capture (rating + reason) and listing | 5 | 6 |
| CRFD-107 | Dashboard UI: KPI strip, charts, table, drawer, exception queue | 8 | 2–6 |

### Epic B — Platform and delivery toolchain

| ID | Story | Pts | Sprint |
| --- | --- | --- | --- |
| CRFD-110 | `[platform]` Repository structure, `.gitignore`, README, branch and commit rules | 3 | 0 |
| CRFD-111 | `[platform]` Maven build with the wrapper pinned; profiles for selenium / war / mysql | 3 | 1 |
| CRFD-112 | `[platform]` Dockerfile (multi-stage) and Compose stack with Nginx | 5 | 3 |
| CRFD-113 | `[platform]` Docker lifecycle script + evidence (build, tag, run, logs, stop, restart, remove) | 3 | 4 |
| CRFD-114 | `[platform]` Jenkins controller as code (Compose + JCasC + plugins + seed job) | 5 | 4 |
| CRFD-115 | `[platform]` `Jenkinsfile`: checkout / build / test / package / publish | 5 | 5 |
| CRFD-116 | `[platform]` Selenium journeys J1–J5, screenshots on failure, runnable via Maven | 8 | 6 |
| CRFD-117 | `[platform]` **Deliberate defect found by Selenium, fixed, suite rerun green** | 3 | 6 |
| CRFD-118 | `[platform]` Selenium quality gate wired into the pipeline, blocking deployment | 3 | 7 |
| CRFD-119 | `[platform]` Docker build/tag/publish/deploy stages in the pipeline | 5 | 7 |
| CRFD-120 | `[platform]` Ansible roles and playbooks: provision, deploy, healthcheck, rollback | 8 | 7 |
| CRFD-121 | `[platform]` **Defect: client errors returned HTTP 500** — fixed, regression tests added | 2 | 7 |
| CRFD-122 | `[platform]` Provisioning evidence: idempotency (changed=0) and rollback | 5 | 7 |
| CRFD-123 | `[platform]` Release baseline: annotated tags, release notes, changelog | 2 | 7 |

### Epic C — Assurance

| ID | Story | Pts | Sprint |
| --- | --- | --- | --- |
| CRFD-130 | Unit + API integration test suite (32 tests) | 5 | 1–6 |
| CRFD-131 | End-to-end acceptance walkthrough of all six capabilities | 3 | 7 |
| CRFD-132 | Troubleshooting guide, limitations and future enhancements | 3 | 7 |
| CRFD-133 | Report pack, viva preparation | 5 | 7 |

**Total: 89 points over 8 sprints.**

## Sprint plan

### Sprint 0 — Inception (week 1)

**Goal:** agree what is being built and how it will be judged.

- CRFD-100 inception and scope freeze
- CRFD-110 repository skeleton, branch/commit rules, issue and PR templates
- Spike: Maven profile layout for JAR vs. WAR, and H2 file mode vs. MySQL

**Review outcome:** scope frozen at six capabilities. Two spikes de-risked the
two decisions that would be expensive to reverse (build tool and database).
**DoD applied from this sprint onward** — the repository, not a document, is the
deliverable.

### Sprint 1 — Ingestion (weeks 2–3)

**Goal:** events can be published, are idempotent, and are classified.

- CRFD-101, CRFD-101b, CRFD-101c, CRFD-101d
- CRFD-111 Maven build and wrapper

**Review outcome:** `POST /api/v1/events` returns `201` + `Location`; a repeated
`eventId` returns `409` **and raises a `DUPLICATE_EVENT` alert** — the rejection is
itself evidence. Pipeline latency is simulated so the latency thresholds are
exercised.
**Retro:** the first transaction-boundary bug surfaced here (writes dispatched
from an `afterCommit` hook were silently discarded). Fixed with `REQUIRES_NEW`;
recorded in `docs/11`.

### Sprint 2 — Search (weeks 4–5)

**Goal:** an engineer can find one event among many.

- CRFD-102 search (free text + facets + paging + sorting)
- CRFD-102b CSV export
- CRFD-107 (part 1): table, filters, paging in the UI

**Review outcome:** 5000-row export cap agreed and enforced, because an unbounded
CSV is a denial-of-service vector disguised as a feature.

### Sprint 3 — Indicators (weeks 6–7)

**Goal:** the dashboard answers "is the pipeline healthy?" before any digging.

- CRFD-103 summary indicators, CRFD-103b time series
- CRFD-107 (part 2): KPI strip, donut, breakdowns
- CRFD-112 Dockerfile + Compose + Nginx

**Review outcome:** the summary is computed with aggregate queries, not by loading
events into memory — the difference is invisible at 180 rows and fatal at 180
million.

### Sprint 4 — Drill-down (weeks 8–9)

**Goal:** an engineer can trace one recommendation.

- CRFD-104 drill-down and timeline, CRFD-104b manual transition + reprocess
- CRFD-113 Docker lifecycle script and evidence
- CRFD-114 Jenkins controller as code

**Review outcome:** every status transition appends an `AuditEntry`; the timeline
is ordered deterministically by `(createdAt, id)` because two transitions can
share a timestamp and the order they are displayed in must be stable.

### Sprint 5 — Exceptions (weeks 10–11)

**Goal:** nothing fails silently.

- CRFD-105 automatic raising, CRFD-105b the exception queue
- CRFD-107 (part 3): exception view UI
- CRFD-115 pipeline stages through packaging and report publishing

**Review outcome:** a threshold breach raises an alert; a *repeat* breach of the
same code on the same event does **not** create a second open alert (suppression),
because an alert queue that fills with duplicates gets ignored — and an ignored
queue is worse than no queue.

### Sprint 6 — Feedback and end-to-end tests (weeks 12–13)

**Goal:** quality is measured explicitly, and the UI is proven by a real browser.

- CRFD-106 explicit feedback
- CRFD-116 the five Selenium journeys
- CRFD-117 **the deliberate defect, found by Selenium, fixed, suite rerun green**
- CRFD-130 test suite completed

**Review outcome:** J2 and J4 failed on the first full run. They were right to.
The event entry path minted an opaque UUID business key while the rest of the
system used `EVT-`-prefixed keys. The journeys failed and the suite produced the
screenshot, page source and diagnostic text that made the cause obvious in
minutes. Full write-up: `docs/11`.

### Sprint 7 — Release (weeks 14–15)

**Goal:** ship it, prove it, and write down what is wrong with it.

- CRFD-118 Selenium quality gate blocking deployment
- CRFD-119 image build/publish/deploy stages
- CRFD-120 Ansible roles and playbooks
- CRFD-121 the HTTP 500 defect fixed with regression tests
- CRFD-122 provisioning evidence: idempotency and rollback
- CRFD-123 annotated tags and release notes
- CRFD-131/132/133 acceptance walkthrough, troubleshooting, report, viva

**Review outcome:** the whole chain runs end to end, and the second run of the
provisioning playbook reports `changed=0`.

## Velocity and burndown

| Sprint | Commitment | Completed | Notes |
| --- | --- | --- | --- |
| 0 | 8 | 8 | Two spikes included |
| 1 | 19 | 19 | First transaction-boundary defect absorbed inside the sprint |
| 2 | 13 | 13 | |
| 3 | 16 | 16 | |
| 4 | 11 | 11 | |
| 5 | 18 | 18 | |
| 6 | 14 | 14 | Selenium found a real defect; the fix stayed inside the sprint |
| 7 | 22 | 22 | Two defects fixed and both pieces of provisioning evidence produced |

Average velocity ≈ 15 points per sprint. The burndown is deliberately *not*
smooth: sprint 1 and sprint 5 both spiked because a sprint composed mostly of API
work completes in one visible step, while a sprint containing a UI story looks
flat for several days and then drops.

The useful observation is that **no sprint was carried over**, which is the
consequence of the Definition of Ready: stories that were not observable were not
allowed into a sprint in the first place.

## The DevOps lifecycle for this project

The classic eight stages, each one made concrete and pointed at the artefact that
proves it:

```
 ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
 │  PLAN    │──▶│  CODE    │──▶│  BUILD   │──▶│  TEST    │
 │          │   │          │   │          │   │          │
 │ docs/01  │   │ src/     │   │ mvnw     │   │ 32 unit+ │
 │ docs/02  │   │ BRANCHING│   │ pinned   │   │ integ    │
 │ backlog  │   │ .github/ │   │ 3.9.16   │   │ + 5 E2E  │
 └──────────┘   └──────────┘   └──────────┘   └────┬─────┘
      ▲                                            │
      │ feedback: retrospectives,                   ▼
      │ defect write-ups (docs/11)        ┌──────────────┐
      │                                   │   RELEASE    │
      │                                   │              │
      │                                   │ tags v0.1.0  │
      │                                   │ v1.0.0, JAR  │
      │                                   │ + checksums  │
      │                                   └──────┬───────┘
      │                                          │
      │                                          ▼
 ┌────┴─────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
 │  MONITOR │◀──│ OPERATE  │◀──│  DEPLOY  │◀──│  PACKAGE │
 │          │   │          │   │          │   │          │
 │ /health  │   │ nginx,   │   │ Jenkins  │   │ Docker   │
 │ /health/ │   │ container│   │ stage 8  │   │ multi-   │
 │ slo,     │   │ lifecycle│   │ fresh    │   │ stage,   │
 │ alerts,  │   │ + rollback│  │ container│   │ 401MB,   │
 │ Ansible  │   │ (docs/12,│   │ (docs/13)│   │ JRE only │
 │ health-  │   │ docs/14) │   │          │   │ (docs/12)│
 │ check.yml│   │          │   │          │   │          │
 └──────────┘   └──────────┘   └──────────┘   └──────────┘
```

| Stage | This project | Artefact |
| --- | --- | --- |
| **Plan** | Frozen scope, 89-point backlog, DoD, sprint plan | `docs/01`, `docs/02`, GitHub issues |
| **Code** | Feature branches, Conventional Commits, PRs with review | Git history, `.github/` |
| **Build** | Maven wrapper, deterministic dependency resolution, profiles | `pom.xml`, `mvnw`, `.mvn/` |
| **Test** | 12 unit + 20 API integration + 5 Selenium journeys; failure screenshots | `target/surefire-reports`, `target/failsafe-reports` |
| **Release** | Annotated tags, JAR + checksums archived by the pipeline | `v0.1.0-mvp`, `v1.0.0` |
| **Package** | Multi-stage Docker image, JRE-only runtime, non-root | `Dockerfile`, `crfd:1.0.0` |
| **Deploy** | Jenkins deploys a fresh container; Ansible does the same over SSH | `Jenkinsfile` stages 6–9, `playbooks/deploy.yml` |
| **Operate** | Container lifecycle, Nginx in front, comprehensive rollback | `scripts/docker-lifecycle.*`, `playbooks/rollback.yml` |
| **Monitor** | Readiness + SLO endpoints, exception queue, scheduled health check | `HealthService`, `playbooks/healthcheck.yml` |

The loop closes back to **Plan**: the defects the test stage found (CRFD-117,
CRFD-121) became backlog items with their own regression tests, which is the only
version of "monitor" that actually changes anything.
