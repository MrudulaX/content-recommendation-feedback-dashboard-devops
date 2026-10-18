# 11 — Continuous testing, the defects it caught, and the fixes

## 1. What "continuous testing" means here

Testing in this project is not a phase. It is wired into three points in the
delivery chain, each catching a class of defect the previous one cannot:

| Point | Runs | Catches | Cost of a miss |
| --- | --- | --- | --- |
| Every local build | `mvn -B clean verify` — 11 unit + 20 API tests | Logic errors, contract errors, wrong status codes, threshold maths | Seconds |
| Every pipeline build, stage 2 | The same 31 tests, in CI, on a clean checkout | "Works on my machine", dependency drift | Minutes |
| Every pipeline build, stage 5 | 5 Selenium journeys against the built image | The product is not actually usable | A broken release |

The pipeline order enforces the economics: the cheap tests run first and the
expensive one runs only if they pass. And the expensive one is placed **after the
image is built and before anything is published or deployed**, so a red gate costs
nothing but time.

## 2. The test pyramid, concretely

| Layer | Count | Class | What it proves |
| --- | --- | --- | --- |
| Unit — services | 5 | `EventServiceTest` | Ingestion, idempotency, quarantine, search behaviour |
| Unit — alerting | 6 | `AlertServiceTest` | Raising, **duplicate suppression**, acknowledge/resolve transitions, illegal transitions |
| API integration | 20 | `EventApiTest` | Every MVP capability over the real Spring context and a real database |
| End-to-end | 5 | `DashboardJourneyIT` | The five critical journeys through a real browser |

**31 automated tests plus 5 journeys.** The API integration layer is deliberately
the thickest: it exercises the real HTTP stack, real validation, real transactions
and real SQL, so a change that breaks the contract fails there rather than in a
browser twenty minutes later.

Two testing decisions worth naming:

**`crfd.processing.async-enabled=false` in the test profile.** The pipeline
normally runs after the response, on its own transaction. In tests it runs
synchronously, so an assertion about a `PROCESSED` event is a statement about
behaviour rather than a race against a scheduler. Tests that depend on timing are
tests that fail on a loaded CI box.

**A deliberately tight SLO in the test profile.** `crfd.thresholds.minimum-success-rate`
is `0.50` in tests, so a breached-SLO response can be produced on purpose. The
deployed build runs at a realistic objective (`0.95`) where its ~0.93–0.96 success
rate sits right around the line — which is honest, and is what makes the
readiness/SLO distinction observable in practice.

---

## 3. Defect CRFD-117 — the one the browser caught

This is the defect the brief asks for: introduced, detected by the automated
gate, fixed, and the suite rerun green.

### 3.1 What was wrong

Two things, found in sequence. The first is why the second was found.

**(a) The test read the DOM unsafely — a false failure.**

J2 ("a submitted feedback event is accepted and becomes searchable") failed on the
first full run:

```
cause=org.openqa.selenium.StaleElementReferenceException: stale element reference:
      stale element not found in the current frame
Element: [...] -> css selector: #events-body tr[data-event-id] td:nth-child(4) div
```

The cause is a race, not a missing feature. The dashboard refreshes its event table
on a timer and re-renders it after a submit. The test had held an `WebElement`
reference to a cell, and by the time it called `getText()` the row had been
replaced — so the reference pointed at a detached node.

This is the worst kind of test failure: **it is not about the product at all.** A
suite that produces false failures is a suite people learn to ignore, and an
ignored suite is worse than none, because it looks like coverage.

The screenshot, page source and diagnostic text from this run are preserved in
`docs/evidence/selenium-defect-crfd-117/` — they are the evidence of the failure,
and the `.txt` is the artefact that made the diagnosis a two-minute job rather than
an afternoon.

*Fix:* reads were routed through a stale-tolerant `await(...)` that re-locates the
element on each poll instead of reusing a reference, and rows/alerts became
addressable by identity (`data-event-id`, `data-alert-ref`) with helpers such as
`fieldOf(alertRef, ".alert-code")` that resolve identity and selector together.
Nothing about the product changed. Detail in
`docs/10-selenium-test-plan.md` §4.

**(b) The event write path minted the wrong kind of business key.**

With the reads fixed, the journeys failed for a real reason. Every other part of
the system — the API contract, the dashboard, the smoke test, the Ansible health
assertions — refers to an event by a human-readable, prefixed business key:
`EVT-` followed by twelve uppercase hex characters. The **write** path was the one
place that did not: it minted a raw UUID.

So the product was internally inconsistent. An event created through the API had a
key of the form `3f2a1c8e-...` while everything downstream expected `EVT-7F3A91C4`,
and:

- **J2 failed** on `assertThat(text(By.id("event-form-message"))).contains("EVT-")`
  — the confirmation message showed the UUID.
- **J4 failed** on `assertThat(text(By.id("detail-event-id"))).startsWith("EVT-")`
  — the drill-down header showed the UUID.

The unit and API tests had all passed, because none of them asserted on the *shape*
of a server-minted key. This is exactly the class of defect that only an
end-to-end test finds: the code is "correct" against its own mock-free assertions
and wrong against the product's real conventions.

*Fix:* key minting was centralised in a single generator,
`RecommendationEvent.newEventId()`, used by every write path, so there is exactly
one place in the codebase that decides what a business key looks like.

### 3.2 Why this is the right defect to have found

It satisfies every property a good "found by the gate" defect should have:

| Property | Here |
| --- | --- |
| It is a **product** defect, not a test artefact | Yes — (b) is a genuine internal inconsistency |
| It is **invisible** to unit and API tests | Yes — all 31 were green while the product was inconsistent |
| It is **immediately visible** in the UI | Yes — the key is on screen in two journeys |
| The failure **names the capability** | Yes — J2 = ingestion, J4 = drill-down |
| The fix is **small and central** | Yes — one generator |
| It has a **regression test** | Yes — J2 and J4 assert the `EVT-` shape |

### 3.3 The rerun

After both fixes, the suite was rerun and passed **5/5, twice**, in two
environments:

| Run | Application | Browser | Result |
| --- | --- | --- | --- |
| 1 | local fat JAR on `:18080` | remote Chrome container | **5/5 pass** |
| 2 | container `crfd:1.0.0`, `http://crfd-app:8080` | `selenium/standalone-chrome:4.25.0`, Chrome 130 | **5/5 pass** |

Log: `docs/evidence/selenium-remote-run.log`. The failure evidence from the
failing run was deliberately **kept** rather than cleaned up — a defect write-up
whose only artefact is a green run afterwards is a claim, not a record.

---

## 4. Defect CRFD-121 — client errors returned `500`

Found while exercising the API by hand, not by a test, which is worth admitting.

### 4.1 What was wrong

The `GlobalExceptionHandler` had a catch-all for `Exception`. Two framework
exceptions that represent *client* mistakes were falling into it:

| Request | Should return | Returned |
| --- | --- | --- |
| `GET /api/v1/nope` (no such path) | `404` | **`500`** |
| `GET /api/v1/events/{eventId}/status` (a real path, wrong verb — it is `PATCH`) | `405` | **`500`** |

The consequences were worse than cosmetic:

1. A client could not distinguish "you asked wrongly" from "we broke". A `500`
   tells a caller to report a bug; a `404` tells it to fix the URL. Both were
   being told the same lie.
2. A `500` is an **alerting signal**. A typo in a URL produced what looks like a
   server fault, which is how alerting gets muted.
3. The dashboard's own error handling was built on the wrong premise — it treated
   every server error as retryable.

### 4.2 The fix

`NoResourceFoundException` and `HttpRequestMethodNotSupportedException` are now
handled explicitly and mapped to `404` and `405`, with the same uniform `ApiError`
body as every other error. The catch-all still exists for genuinely unexpected
faults, and still logs a stack trace and returns a correlation id rather than the
trace.

```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<ApiError> unmappedUrl(...)      // -> 404

@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
public ResponseEntity<ApiError> wrongVerb(...)        // -> 405
```

Regression tests: `unmappedUrlReturnsNotFoundInsteadOfServerError` and
`wrongVerbReturnsMethodNotAllowed`. Both fail against the old code, which is the
only property that makes a regression test worth having.

---

## 5. A design correction — readiness vs. the SLO gate

Not a defect in the sense of a coding error, but a decision that was wrong and was
corrected, and it is the most instructive thing in this document.

**The original design** had one health endpoint: `200` when healthy, `503`
whenever anything was off — including a pipeline success rate below its objective.
Seeding the database produced a success rate around `0.90` against a `0.95`
objective, so the endpoint returned `503`.

**What that broke, in order:**

1. The container's `HEALTHCHECK` failed, so the container reported `unhealthy`.
2. Docker Compose's `depends_on: condition: service_healthy` meant **Nginx never
   started**.
3. The Ansible role's health wait timed out and **failed the deployment**.
4. Nothing was actually wrong with the application. It was serving every request
   correctly. A reporting number had taken a working node out of service.

**The correction:** two endpoints, because there are two questions and two
consumers.

| Endpoint | Question | `503` when | Consumers |
| --- | --- | --- | --- |
| `GET /api/v1/health` | Can this instance serve traffic? | a hard dependency is `DOWN` | container `HEALTHCHECK`, Nginx, the deploy gate, Ansible |
| `GET /api/v1/health/slo` | Is the pipeline meeting its objective? | the success rate is breached | monitoring, alerting, an optional quality gate |

`/api/v1/health` now returns `200` for both `UP` and `DEGRADED` and `503` only for
`DOWN`. The distinction is captured as evidence from a single running instance in
`docs/evidence/health-and-slo.txt`:

```
GET /api/v1/health      -> HTTP 200   {"status":"DEGRADED", ... "sloBreached":true ...}
GET /api/v1/health/slo  -> HTTP 503   {"status":"BREACHED", "pipelineSuccessRate":"0.9027", "sloMinimum":"0.95"}
GET /actuator/health    -> HTTP 200   {"status":"UP", ...}
```

Readiness `200`, business gate `503`, from the same process at the same moment.
That is the whole design in three lines.

Regression tests: `readinessIsIndependentOfTheSloGate` and
`sloGatePassesWhenWithinObjective`.

**The general rule this produced**, which applies well beyond this project: *never
let a business metric's value control whether a node is considered able to serve
traffic.* Those are different questions with different correct responses, and
collapsing them converts a reporting problem into an outage.

---

## 6. The other defects, briefly

Found during the build and fixed with tests where the fix was code:

| # | Defect | Caught by | Fix |
| --- | --- | --- | --- |
| **D1** | Pipeline writes dispatched from a transaction-commit hook were silently discarded — the persistence context was already closed | sprint 1 review, then a failing assertion | `EventProcessingService.process(...)` annotated `@Transactional(propagation = REQUIRES_NEW)`; `crfd.processing.async-enabled=false` for deterministic tests |
| **D2** | A threshold breach repeated on the same event created a second open alert, filling the queue with duplicates | `AlertServiceTest` | Suppress a new `OPEN` alert when one already exists for the same `(code, eventRef)` |
| **D3** | Acknowledging an already-`RESOLVED` alert succeeded, producing an alert that was neither open nor resolved | `AlertServiceTest` | Reject with `409` |
| **D4** | The timeline order was not stable when two transitions shared a timestamp | `EventApiTest` (flaky, then deterministic) | Order by `(createdAt, id)` |
| **D5** | An unbounded page size let a single request load the whole table | review | `size` clamped to 1–200; CSV export capped at 5000 rows |

D2 is the one with a product lesson attached: an alert queue that fills with
duplicates of one problem gets ignored, and an ignored queue is worse than no
queue. Suppression is not an optimisation, it is what makes the queue usable.

## 7. Where the tests live, and why

```
src/test/java/com/crfd/dashboard/
├── service/
│   ├── EventServiceTest.java        5  unit — ingestion, idempotency, quarantine, search
│   └── AlertServiceTest.java        6  unit — raising, suppression, lifecycle, illegal transitions
├── web/
│   └── EventApiTest.java           20  integration — MockMvc + real context + real H2
└── selenium/
    ├── SeleniumTestSupport.java       driver factory, stale-tolerant await, failure evidence
    └── DashboardJourneyIT.java      5  end-to-end — the five critical journeys
```

The split follows the failure the test is designed to catch, not the layer the
code happens to live in:

- a defect in **thresholds, transitions or suppression** is a service test;
- a defect in the **HTTP contract** — status codes, validation, serialisation,
  error bodies — is an API test;
- a defect in **whether a human can use it** is a Selenium journey.

## 8. Definition of Done, as it applies to testing

From `docs/02-agile-plan.md`, and actually enforced rather than aspirational:

- [x] Acceptance criteria covered by an automated test
- [x] `mvn -B test` green
- [x] Journeys updated and green for any change touching a critical journey
- [x] Selenium failure produces screenshot + page source + diagnostic text
- [x] Failing journeys block the deployment (stage ordering)
- [x] Every defect found produced either a regression test or a written reason why
      it could not

## 9. Verification status

| Suite | Count | Command | Result |
| --- | --- | --- | --- |
| Unit | 11 | `mvn -B test` | pass |
| API integration | 20 | `mvn -B test` | pass |
| End-to-end | 5 | `mvn -B -P selenium verify` | pass, twice, two environments |
| Deployment smoke | 11 | `scripts/smoke-test.ps1 -BaseUrl http://localhost:18080` | pass |

Evidence: `docs/evidence/smoke-test.txt`, `docs/evidence/selenium-remote-run.log`,
`docs/evidence/selenium-defect-crfd-117/`, `docs/evidence/health-and-slo.txt`.
