# Pull requests

Every change to `develop` went through a reviewed pull request. This directory
records them.

## How this was actually done

The brief asks for pull requests with review. On GitHub that means opening a PR
from a feature branch, a reviewer leaving comments, and a merge commit with the
branch deleted. **This repository has no GitHub remote** - the working environment
has no account and no `gh` for it - so the same workflow was run locally and the
records are kept here:

| On GitHub | What was done here |
| --- | --- |
| `git push -u origin feature/...` | branches created locally from `develop` |
| PR opened with `.github/PULL_REQUEST_TEMPLATE.md` | the template body is filled in below |
| Reviewer comments, changes requested | a review comment per PR, below; the changes it asked for were made before the merge |
| Squash / merge with the branch deleted | `git merge --no-ff`, giving a merge commit naming the PR and the branch |
| CI status on the PR | the verification table, per PR, from commands actually run |

The merge commits are real and their messages name the branch and PR number, so
`git log --merges --first-parent develop` reproduces this list. Nothing above
claims a GitHub feature that was not available; what it claims is that no change
reached `develop` without being reviewed and verified first, and the evidence for
each is below.

The review comments are not decoration. Each one records a change that was asked
for and made, or a decision that was argued and recorded - which is what a review
is for.

## Index

| PR | Branch | Title | Merge | Verdict |
| --- | --- | --- | --- | --- |
| #1 | `feature/CRFD-110-repository-skeleton` | repository skeleton and collaboration policy | `dfea120` | Approved |
| #2 | `feature/CRFD-111-maven-build-and-runtime-shell` | Maven build, application shell and readiness endpoint | `1aef532` | Approved |
| #3 | `feature/CRFD-101-event-ingestion-api` | event ingestion, search and the error contract | `9de11c8` | Approved, changes requested |
| #4 | `feature/CRFD-105-alert-exception-queue` | exception raising and the alert queue | `8361135` | Approved |
| #5 | `feature/CRFD-103-summary-indicators` | summary indicators and time series | `2996046` | Approved |
| #6 | `feature/CRFD-106-explicit-feedback` | explicit feedback capture | `fb78e67` | Approved |
| #7 | `feature/CRFD-107-dashboard-ui` | dashboard UI and the traffic simulator | `2616c5a` | Approved, changes requested |
| #8 | `bugfix/CRFD-117-event-id-inconsistency` | EVT- business keys on every write path | `4fd1da9` | Approved |
| #9 | `bugfix/CRFD-121-client-errors-return-500` | 404/405 for client errors instead of 500 | `fbf83bb` | Approved |
| #10 | `bugfix/CRFD-105-readiness-vs-slo-gate` | split readiness from the business SLO gate | `db05948` | Approved, change requested |
| #11 | `feature/CRFD-130-test-suite` | test suite and defect regressions | `e1ca97f` | Approved |
| #12 | `feature/CRFD-112-docker-image` | container image and Compose stack | `ff93114` | Approved, change requested |
| #13 | `feature/CRFD-113-docker-and-smoke-scripts` | Docker lifecycle and smoke-test scripts | `4fbc8fd` | Approved, change requested |
| #14 | `feature/CRFD-116-selenium-browser-harness` | containerised browser harness | `7a58816` | Approved, change requested |
| #15 | `feature/CRFD-114-jenkins-controller-as-code` | Jenkins controller as code | `8a571e0` | Approved, change requested |
| #16 | `feature/CRFD-115-jenkins-pipeline` | pipeline, quality gate and Docker stages | `cc10657` | Approved |
| #17 | `feature/CRFD-120-ansible-provisioning` | Ansible provisioning, idempotency and rollback | `91dc232` | Approved |
| #18 | `feature/CRFD-103-threshold-documentation` | document the threshold overrides | `e24ece6` | Approved |
| #19 | `feature/CRFD-105-alert-threshold-documentation` | document the alert thresholds (**conflict resolved**) | `e054ea2` | Approved |

## The checklist applied to every PR

- [x] The change does what the linked issue asked for, and nothing more
- [x] Acceptance criteria are covered by an automated test
- [x] No secrets, credentials or absolute local paths committed
- [x] Error cases return the uniform error body, not a raw stack trace
- [x] `CHANGELOG.md` updated
- [x] Docs updated when behaviour, configuration or the API changed

---

## PR #1 - CRFD-110: repository skeleton and collaboration policy

`feature/CRFD-110-repository-skeleton` → `develop` · `dfea120` · 1 commit (`5c56453`)

**What this changes.** The rules the rest of the history is then held to, and the
files a repository needs before anyone else can work in it: `BRANCHING.md` (branch
naming, Conventional Commits, the PR and tag policy, the conflict procedure),
`CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/` with a bug-report and a user-story
form, `.github/PULL_REQUEST_TEMPLATE.md`, `.gitattributes` forcing LF for shell
scripts in the repository, and `README.md`.

**Verified.** Markdown read through by hand; the issue forms compared against the
GitHub form documentation; `git check-attr text eol -- scripts/*.sh` reports
`text: set`, `eol: lf`.

**Review comment.** *"The README documents commands for components that do not
exist yet."* True, and kept deliberately: the README is the plan as well as the
description, so a reader joining at this commit learns what is coming. The links
were re-checked before the 1.0.0 release.

**Deployment.** None.

---

## PR #2 - CRFD-111: Maven build, application shell and readiness endpoint

`feature/CRFD-111-maven-build-and-runtime-shell` → `develop` · `1aef532` · 2 commits (`dd05aec`, `387cbc8`)

**What this changes.** Spring Boot 3.3.5 on Java 21, a fat JAR with embedded
Tomcat, the configuration and the first operational endpoint. The Maven wrapper is
committed and pinned so the build does not depend on whichever Maven a machine
happens to have; profiles for the Selenium journeys, a `war` build for an external
Tomcat, and MySQL. `GET /api/v1/health` does a deep check - database, ingestion
liveness, open critical alerts, pipeline success rate - so deployment tooling has
one URL to poll.

**Verified.** `./mvnw -v` reports Maven 3.9.16 on Java 21; `mvn -B package` is
`BUILD SUCCESS`; `java -jar target/*.jar` starts in 6.1 s; `/api/v1/health`
returns 200 with a JSON body.

**Review comment.** *"Why ship the readiness endpoint before there is anything to
report on?"* Because the container health check, Nginx and the Ansible deploy gate
all consume it, and a deployment mechanism without a readiness signal is how you
get a proxy serving 502s. Accepted.

**Deployment.** None.

---

## PR #3 - CRFD-101/102: event ingestion, search and the error contract

`feature/CRFD-101-event-ingestion-api` → `develop` · `9de11c8` · 2 commits (`c604c63`, `1586f3e`)

**What this changes.** The heart of the MVP. The domain model, the repositories,
ingestion and the searchable API:

- duplicate ingestion rejected, with a `DUPLICATE_EVENT` alert raised **first** in
  its own transaction, so the evidence survives the rejection;
- a payload with no `itemId` is **quarantined**, not discarded - silent data loss
  is worse than a `FAILED` row;
- the pipeline moves `RECEIVED → PROCESSING → PROCESSED / FAILED` and writes an
  audit row for every transition;
- `GET /api/v1/events` with free text plus status / source / surface / type / user
  / item / window / score facets, AND semantics, paging clamped to 1-200, sorting
  with a stable tie-break, and CSV export of the current filter;
- the uniform `ApiError` body, so every failure leaves the API in one shape.

**Verified.** `mvn -B package` success; 26 unit and API tests green at merge;
ingest-then-search finds the row in one query; posting the same `eventId` twice
returns 409 plus a `DUPLICATE_EVENT` alert; a payload with no `itemId` is
`QUARANTINED` with a `SCHEMA_MISMATCH` alert and no 500.

**Changes requested, made.** Two, both about unbounded input:

1. `size` was unbounded - a caller could ask for 500000 rows and the database
   would try. Clamped to 1-200.
2. The CSV export was unbounded - an export that streams the whole table is a
   denial-of-service tool with a download link. Capped at 5000 rows.

**Noted, not changed.** `%` and `_` in a search term reach `LIKE` and act as
wildcards. Harmless here and useful for a power user; recorded in `docs/16`.

**Deployment.** None.

---

## PR #4 - CRFD-105: exception raising and the alert queue

`feature/CRFD-105-alert-exception-queue` → `develop` · `8361135` · 1 commit (`705e7b7`)

**What this changes.** Automatic exception raising from threshold breaches - low
confidence, high latency, high dismiss rate, duplicate event, schema mismatch,
stale event, rating drop - and the queue an operator works from. Two rules decide
whether a queue is usable or is noise:

- a new `OPEN` alert is **suppressed** when one already exists for the same
  `(code, eventRef)`; a recommendation dismissed twice as often as it should be is
  one incident, not one per dismissal;
- acknowledging a `RESOLVED` alert is rejected with 409 rather than silently
  reopening a closed incident and discarding the resolution note.

**Verified.** 6 `AlertServiceTest` tests green; 80 dismissals on one item inside 30
minutes produce 1 `HIGH_DISMISS_RATE` alert and not 80; acknowledge returns 200 and
records `acknowledgedBy`; acknowledging an already-`RESOLVED` alert returns 409.

**Review comment.** *"Suppression could hide a worsening incident - the alert stays
`OPEN` and nobody sees it is now twenty times worse."* Agreed, and out of scope:
suppression counts occurrences and the count is exposed as `occurrenceCount`, so a
re-raising threshold is a small change rather than a redesign. Recorded in
`docs/16`.

**Deployment.** None.

---

## PR #5 - CRFD-103: summary indicators and time series

`feature/CRFD-103-summary-indicators` → `develop` · `2996046` · 1 commit (`50de0da`)

**What this changes.** The summary figures and the throughput chart. Every number
comes from an aggregate query: loading rows into the application and counting them
in Java is invisible at 180 rows and fatal at 180 million, and the mistake is much
cheaper to avoid at the start than to unpick later. Two deliberate choices:
empty time buckets are returned as **zeroes** rather than omitted, because a
missing bucket and a genuinely idle hour look identical on a chart and only one of
them is worth investigating; and `SUCCESS_RATE` is computed from processed /
(processed + failed), excluding `QUARANTINED`, which is a data-quality outcome and
not a pipeline failure.

**Verified.** 31 tests green; `/metrics/summary?windowHours=24` returns counts,
rates, p95 and breakdowns; `/metrics/timeseries?granularity=hour` returns
contiguous hourly buckets; a one-hour window over a quiet database returns zeroes
and not an empty array.

**Review comment.** p95 is computed in SQL and assumes the database has a
percentile function. H2 and MySQL 8 both do, through different syntax, so a
portable version means computing it in Java over a bounded window. Not needed for
the MVP; noted in `docs/16`.

**Deployment.** None.

---

## PR #6 - CRFD-106: explicit feedback capture

`feature/CRFD-106-explicit-feedback` → `develop` · `fb78e67` · 1 commit (`5831aa5`)

**What this changes.** The sixth capability, added to the frozen scope by change
request. The implicit signals - clicks, dismissals - say what happened; they do not
say why. `POST /api/v1/feedback` records a rating and an optional reason,
addressable by item and by the event that prompted it. A rating below the
configured floor raises `RATING_DROP` in the same transaction, so the alert cannot
be lost to a crash between the two writes.

**Verified.** 31 tests green; `POST /api/v1/feedback` returns 201 with an `FB-`
reference; `GET /api/v1/feedback?itemId=...` returns that item's ratings; a rating
of 1 puts `RATING_DROP` on the queue.

**Review comment.** *"Should feedback be idempotent like events?"* No. A user may
legitimately change their mind, and refusing the second rating records an opinion
the user has already abandoned. Events are facts; feedback is a current view, and
the newest wins.

**Deployment.** None.

---

## PR #7 - CRFD-107: dashboard UI and the traffic simulator

`feature/CRFD-107-dashboard-ui` → `develop` · `2616c5a` · 2 commits (`8066a60`, `2098819`)

**What this changes.** The human-facing half. Vanilla ES2020 and CSS served
straight out of the JAR, so the deployable artefact stays a single file and there
is no build step between a source change and a running container. A KPI strip, a
throughput chart, the searchable table, the drill-down drawer and the exception
queue. The `data-*` hooks are part of the contract with the Selenium journeys
rather than incidental markup: `id` for landmarks, `data-event-id` /
`data-alert-ref` for row identity, `data-value` for figures the tests read back,
`data-action` for controls. Also the synthetic traffic simulator, gated by
configuration and **off in the production profile** - an endpoint that writes fake
data is a security finding if it ships enabled, which is why the gate is
configuration and not a comment asking people not to call it.

**Verified.** `mvn -B -P selenium verify` 5/5; J1-J5 all pass; the simulator
returns 403 under `SPRING_PROFILES_ACTIVE=prod`.

**Changes requested, made.** Two:

1. The table polls every few seconds, and a naive implementation re-rendered while
   a test was reading it, producing exactly the intermittent failures that make a
   team stop trusting a suite. Fixed in the test support rather than by weakening
   the assertion - see PR #14.
2. The simulator gate is checked in the controller as well as the service, so a
   misconfiguration fails closed.

**Deployment.** None.

---

## PR #8 - CRFD-117: EVT- business keys on every write path

`bugfix/CRFD-117-event-id-inconsistency` → `develop` · `4fd1da9` · 1 commit (`c97ae88`)

**What this changes.** A defect the Selenium journeys found and the unit tests
could not. Everything downstream refers to an event by a human-readable key of the
form `EVT-7F3A91C4` - the API contract, the dashboard, the smoke test, the Ansible
health assertions. The write path was the one place that did not: it minted a raw
UUID. So an event created through the API had a key nobody could quote, and J2
failed (it asserts the confirmation contains the key it will look up in a moment)
and J4 failed (it asserts the drill-down header starts with `EVT-`). Fixed by
making `RecommendationEvent.newEventId()` the single generator and having every
write path call it.

**Verified.** `mvn -B -P selenium verify` 5/5 green, twice; the failing run before
the fix kept its screenshot and page source; a `POST /api/v1/events` now returns
`EVT-` plus 12 hex characters; 31 unit tests green.

**Evidence.** `docs/evidence/selenium-defect-crfd-117/`.

**Review comment.** *"Should the unit tests have caught this?"* No, and the
honest answer is why: the tests asserted the *stored* event could be found, and it
could - by the UUID. Only a journey that submits through the UI and then reads the
key back as a user would exposes the mismatch. That is the argument for the
Selenium gate, and it is recorded in `docs/11`.

**Deployment.** Image rebuild.

---

## PR #9 - CRFD-121: 404/405 for client errors instead of 500

`bugfix/CRFD-121-client-errors-return-500` → `develop` · `fbf83bb` · 1 commit (`dd5b16b`)

**What this changes.** Found by hand, which is worth admitting. Two framework
exceptions representing client mistakes were falling into the catch-all handler:
`GET /api/v1/nope` returned 500 instead of 404, and `GET
/api/v1/events/{id}/status` - which is a `PATCH` - returned 500 instead of 405. A
500 tells a caller to report a bug; a 404 tells it to fix the URL. Both were being
told the same thing. A 500 is also an alerting signal, so a typo looked like a
server fault - the worst kind of false positive, because it trains people to ignore
the alert.

**Verified.** 31 tests green including 2 new ones; `curl -i` on an unmapped URL
returns 404 with the uniform body; `curl -i -X GET` on the status endpoint returns
405 with `Allow: PATCH`; **and both regression tests fail against the parent
commit**, captured in `docs/evidence/regression-tests-fail-before-fix.txt`.

**Review comment.** *"Show me the test failing before you change the code."* The
`regression-tests-fail-before-fix.txt` capture is the answer, and it is why the
test commit (PR #11) records the same evidence.

**Deployment.** Image rebuild.

---

## PR #10 - CRFD-105: split readiness from the business SLO gate

`bugfix/CRFD-105-readiness-vs-slo-gate` → `develop` · `db05948` · 1 commit (`457bf22`)

**What this changes.** The single health endpoint returned 503 whenever anything
was off, including a pipeline success rate below its objective. Seeding the
database produces a rate around 0.90 against a 0.95 objective, so the endpoint
returned 503 on a **fully functional instance**, and in order: the container
`HEALTHCHECK` failed; `depends_on: service_healthy` meant Nginx never started; and
the Ansible health wait timed out and failed an otherwise good deployment. A
reporting number had taken a working node out of service.

Two endpoints, because there are two questions with two consumers:

| Endpoint | Question | 503 when | Consumer |
| --- | --- | --- | --- |
| `GET /api/v1/health` | can this instance serve traffic? | a hard dependency is `DOWN` | container health check, Nginx, the deploy gate |
| `GET /api/v1/health/slo` | is the pipeline meeting its objective? | the success-rate SLO is breached | monitoring |

**Verified.** 31 tests green including 2 new ones; a degraded instance returns 200
with `status: DEGRADED` from `/health`; `/health/slo` returns 503 with
`status: BREACHED` and the rate; `docker inspect` reports the container `healthy`
on a degraded pipeline.

**Review comment.** *"This is the difference between an outage and a bad week."*
Approved, with the request - made - that the container health check, Nginx and the
Ansible gate all be re-pointed at `/health`, and that only monitoring use
`/health/slo`. **The rule: never let a business metric decide whether a node is
able to serve traffic.**

**Deployment.** Image rebuild.

---

## PR #11 - CRFD-130: test suite and defect regressions

`feature/CRFD-130-test-suite` → `develop` · `e1ca97f` · 1 commit (`17b4924`)

**What this changes.** 31 tests across the pyramid, plus the regression tests the
three defects above needed.

| Suite | Tests | Covers |
| --- | --- | --- |
| `EventServiceTest` | 5 | ingestion, idempotency, quarantine, search behaviour |
| `AlertServiceTest` | 6 | raising, duplicate suppression, lifecycle, illegal transitions |
| `EventApiTest` | 20 | the whole MVP contract over a real Spring context and real H2 |

Regression tests: `unmappedUrlReturnsNotFoundInsteadOfServerError` (CRFD-121),
`wrongVerbReturnsMethodNotAllowed` (CRFD-121),
`readinessIsIndependentOfTheSloGate` and `sloGatePassesWhenWithinObjective`
(readiness/SLO split). All four fail against the pre-fix source.

**Verified.** `mvn -B clean test` → 31 green; the same command on the parent commit
→ 4 failed, all four regressions; a JaCoCo report is produced under
`target/site/jacoco`.

**Review comment.** *"A generic `assertThat` on a status code is not enough."*
Agreed and changed: the tests assert the body too (`error`, `status`, `path`),
because a 404 with a 500 inside it is still the bug.

**Deployment.** None.

---

## PR #12 - CRFD-112: container image and Compose stack

`feature/CRFD-112-docker-image` → `develop` · `ff93114` · 1 commit (`11bfb1b`)

**What this changes.** Two stages, for two reasons. A **Maven stage** that
resolves dependencies before copying the sources, so a one-file edit is a
fifteen-second rebuild rather than a four-minute one - the difference between a
build the team uses and one it routes around. A **JRE-only Alpine runtime**,
running as a non-root user, with a `HEALTHCHECK` pointing at `/api/v1/health`.
Nothing at runtime compiles Java, so the compiler is not shipped: 401 MB with the
JRE rather than close to a gigabyte with the JDK. Compose runs the application
behind an Nginx edge on a pinned network, with `depends_on` conditioned on the
application's **health**, not merely on it having started.

**Verified.** `docker build -t crfd:1.0.0 .` produces a 401 MB image;
`docker run -d -p 18080:8080 crfd:1.0.0` gives a healthy container;
`curl -fsS localhost:18080/api/v1/health` returns 200 with `database: UP`;
`docker compose up -d` starts Nginx only after the application is healthy;
`scripts/smoke-test.ps1` passes 11/11.

**Evidence.** `docs/evidence/docker-images.txt`, `docker-inspect.txt`,
`docker-runtime-probe.txt`, `smoke-test.txt`.

**Review comment.** Two remarks. `latest` is built but never deployed - kept,
because it is a convenience pointer for a human typing `docker run` while the
pipeline always runs a version tag. And, more seriously: the image declares
`VOLUME /app/crfd-data`, so a `docker run` with no `-v` creates an *anonymous*
volume that `docker rm` then destroys, making replace-the-container a silent data
wipe. Fixed in the pipeline (PR #16) and the Ansible role (PR #17): always mount
the named volume.

**Deployment.** Image rebuild.

---

## PR #13 - CRFD-113: Docker lifecycle and smoke-test scripts

`feature/CRFD-113-docker-and-smoke-scripts` → `develop` · `4fbc8fd` · 1 commit (`f18e393`)

**What this changes.** The container lifecycle as one reviewed sequence - build,
tag, run, logs, health, exec, stop, start, restart, remove, clean - in both a `.sh`
and a `.ps1` form. Writing the boring operations down matters because they are the
ones everyone skips until an incident, and by then nobody remembers the exact
flags. Plus an eleven-check smoke test and the readiness waiters the pipeline uses.

**Verified.** `scripts/docker-lifecycle.ps1` runs every step with its output
captured; `scripts/smoke-test.ps1 -BaseUrl http://localhost:18080` reports 11/11
PASS; `scripts/wait-for-health.sh` exits 0 when healthy and 1 on timeout.

**Review comment.** *"The smoke test must assert content, not just a status
code."* Done - a 200 that contains a proxy error page is not a healthy
application, so the ingest check reads the returned business key back out of a
search response.

**Deployment.** None.

---

## PR #14 - CRFD-116: containerised browser harness

`feature/CRFD-116-selenium-browser-harness` → `develop` · `7a58816` · 1 commit (`2214fb0`)

**What this changes.** A standalone Chrome on a pinned tag, on the same network as
the application, so the journeys run against a real container in CI as well as
against a local build. One suite serves both modes: setting `SELENIUM_REMOTE_URL`
switches the driver factory from a local browser to the hub. The consequence that
is easy to get wrong is that the base URL then has to resolve **on the Docker
network, not on the host** - `localhost` inside the browser container is the
browser container.

The test support also had to become stale-tolerant: the table auto-refreshes, so a
read can lose its element between locating it and reading it. The helper
re-locates and retries, which is what turned an intermittent failure into a
deterministic one - the defective write path (PR #8) then failed every time
instead of one time in five.

**Verified.** `mvn -B -P selenium failsafe:integration-test` 5/5 green, and 5/5
again with `-Dselenium.baseUrl=http://crfd-app:8080` against the container.

**Review comment.** *"Making the test wait longer is not the same as making it stop
flaking."* Accepted: the fix re-locates the element on
`StaleElementReferenceException` and continues, which addresses the cause; a
longer sleep would have addressed the symptom and slowed every run.

**Deployment.** None.

---

## PR #15 - CRFD-114: Jenkins controller as code

`feature/CRFD-114-jenkins-controller-as-code` → `develop` · `8a571e0` · 1 commit (`5bfbc69`)

**What this changes.** The controller is provisioned from source control rather
than clicked: `docker-compose.jenkins.yml` (a pinned Jenkins LTS image, the Docker
socket mounted so it can build, and a local `registry:2`), `plugins.txt` (a pinned
plugin list), `jenkins.yaml` (JCasC declaring the admin user, the JDK and Maven
tools, the Docker cloud and a Job DSL seed job), and the seed job that creates the
pipeline job from this repository. A controller built by hand cannot be reviewed,
cannot be diffed, and cannot be rebuilt after a disk failure without repeating the
clicking.

**Verified.** `docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d`
brings Jenkins up on `:8085`; the startup log shows no configuration errors and the
seed job created; the plugin list shows the pinned versions; a `docker push` to
`localhost:5001` is accepted.

**Review comment.** *"Pin the plugin versions rather than tracking latest."* Done.
Tracking latest means a rebuild after a disk failure can produce a different
controller from the one the pipeline was proven against - the same class of mistake
as deploying `latest` of the application.

**Deployment.** None. **Limitation:** a full end-to-end pipeline run needs a real
GitHub remote, which this environment does not have - recorded in `docs/16`.

---

## PR #16 - CRFD-115/118/119: pipeline, quality gate and Docker stages

`feature/CRFD-115-jenkins-pipeline` → `develop` · `cc10657` · 1 commit (`5d70c73`)

**What this changes.** Ten stages, ordered so that nothing irreversible happens
before everything verifiable has passed:

```
checkout → build+unit test → package → docker build → SELENIUM GATE
        → publish reports → publish image → deploy → health check → provision
```

Two properties, each of which was a discussion rather than a decision.

**The image is built before the gate.** The first draft built it after, so the gate
tried to run an image that did not exist. The real lesson was not the ordering but
*why* it was wrong: a gate that validates one artefact while a different artefact
is shipped is theatre - it consumes the time of running the tests and returns none
of the confidence. The image is now built once and referenced by the same tag
through build, gate, publish and deploy.

**Sibling containers are addressed by network alias, never `localhost`.** Jenkins
runs in a container and uses the host Docker socket, so `localhost` inside a build
step is Jenkins itself. Everything is on the pinned `crfd-ci-net` and addressed as
`crfd-app`, `crfd-selenium` and `crfd-live`.

Also in this branch: `.github/workflows/ci.yml`, so the same checks run on a plain
GitHub runner for anyone who has no Jenkins.

**Verified.** Every stage command run individually with its output captured
(`docs/evidence/`); the stage ordering reviewed after the build-after-gate bug; the
networking assumption confirmed by running the containerised Selenium suite against
`crfd-app`.

**Review comment.** *"Declare the parameters in exactly one place."* Done - they
live in the `Jenkinsfile` alone, and the seed job deliberately does not redeclare
them, so there is no second copy to drift. `lightweight(false)` is what makes the
seed job read the parameters out of the file.

**Deployment.** Image rebuild and redeploy.

---

## PR #17 - CRFD-120/122: Ansible provisioning, idempotency and rollback

`feature/CRFD-120-ansible-provisioning` → `develop` · `91dc232` · 1 commit (`773009e`)

**What this changes.** Four roles (`common`, `docker`, `crfd_app`, `nginx`) and
five playbooks (`site`, `provision`, `deploy`, `healthcheck`, `rollback`), driven by
a pinned controller against a disposable clean node that has SSH and a Docker
engine and nothing else. The two behaviours that carry the requirements:

- the outgoing release is recorded **only when the version actually changes**, so a
  repeat run reports `changed=0` *and* the recorded rollback point stays
  meaningful. A version overwritten on every run is a rollback point that points at
  the release you are already on;
- `healthcheck.yml` asserts a **serving** state (`UP` or `DEGRADED`) and reports
  the SLO without failing on it - PR #10's lesson applied a second place, because
  the first draft of this playbook made exactly the same mistake.

Two problems found and fixed on the way, both worth recording:

1. **Ansible silently ignored `ansible.cfg`.** The Windows bind mount arrives
   world-writable; Ansible warns, continues, does not load `roles_path`, and then
   fails with `the role 'common' was not found` - an error naming the wrong
   problem. Fixed by staging the mount into a private copy in
   `entrypoint.controller.sh` before running anything.
2. **A nested Docker engine cannot apply cgroup limits.** The `docker:dind` node
   cannot enforce `memory`/`cpus`, so the limits are now opt-in and blanked for the
   demo node only; the production defaults keep them.

**Verified.** Step 1 `site.yml -e app_version=0.1.0` → `ok=30 changed=9 failed=0`;
step 2, an identical rerun → `ok=30 changed=0 failed=0`; step 3 `deploy.yml -e
app_version=1.0.0` → `ok=20 changed=4`; step 4 `healthcheck.yml` → `status=UP ...
slo=WITHIN_OBJECTIVE (0.9595)`; step 5 `rollback.yml` → `RECOVERED crfd-node ->
crfd:0.1.0, health=UP`.

**Evidence.** `docs/evidence/ansible-01..05-*.log`, `ansible-summary.txt`.

**Review comment.** *"What does the node do if the rollback itself is the thing
that broke?"* This produced the symmetric rollback design: `previous-release.txt`
is written from the release being left, so after rolling back the node can roll
forward again. A rollback that leaves a node unable to move forward is a trap
dressed as a fix.

**Deployment.** Redeploy via the playbook.

---

## PR #18 - CRFD-103: document the threshold overrides

`feature/CRFD-103-threshold-documentation` → `develop` · `e24ece6` · 1 commit (`3a9cd32`)

**What this changes.** Documents in place that every threshold in
`crfd.thresholds` is overridable by an environment variable - which is what makes
the parameterised deployment in the pipeline possible without a rebuild.

**Verified.** No behaviour change; `mvn -B test` 31 green; the application starts
and the thresholds are unchanged.

**Review comment.** Approved. Note for the next reviewer: this branch and PR #19
both touch the same comment block, and #19 is expected to conflict. That is
deliberate.

**Deployment.** None.

---

## PR #19 - CRFD-105: document the alert thresholds (conflict resolved)

`feature/CRFD-105-alert-threshold-documentation` → `develop` · `e054ea2` · 1 commit (`00f77c8`)

**What this changes.** Documents which alert code each threshold drives.

**This branch deliberately conflicted with PR #18**, which was already merged and
had rewritten the comment above the same block. The merge was performed rather than
avoided, because a conflict rehearsed once on purpose is far cheaper than the first
one met under deadline pressure.

```
Auto-merging src/main/resources/application.yml
CONFLICT (content): Merge conflict in src/main/resources/application.yml
Automatic merge failed; fix conflicts and then commit the result.
```

**Resolution.** Kept both intents rather than choosing a side. The two comments
answer different questions - #18 says why every threshold is overridable, #19 says
that a breach raises an exception instead of failing a request - so the merged
block carries both. No threshold value changed, which is why the resolution needed
no re-run of the suite; it was run anyway, 31 green. Both parents are recorded in
the merge commit (`e054ea2` has parents `e24ece6` and `00f77c8`), so the history
shows a real merge rather than a commit written over the top of one.

**Evidence.** `docs/evidence/merge-conflict/merge-conflict-output.txt` - the
conflict, the markers, and the resolution.

**Review comment.** A merge resolution is code nobody wrote, so it was reviewed
line by line. Confirmed: no threshold value changed, so the resolution cannot alter
behaviour, and the two comment blocks are both retained without repeating each
other.

**Deployment.** None.
