# 10 — Selenium end-to-end test plan

## 1. Purpose and scope

Unit and API tests prove the code does what the code is supposed to do. They
cannot prove that a human can *use* the product. The gap they leave is specific
and familiar: the endpoint returns `200` with the right JSON while the dashboard
shows an empty table because a selector changed, a script failed to load, or a
field is rendered but never populated.

The Selenium suite closes exactly that gap. It drives a real browser against a
real running instance and asserts on what a user would see.

**In scope:** the five critical user journeys below, through the UI, against a
deployed build.

**Explicitly out of scope:**

| Not covered | Why | Where it is covered instead |
| --- | --- | --- |
| Every field and every filter combination | Combinatorial and low-yield; the API tests cover the combinations | `EventApiTest` (20 tests) |
| Cross-browser / cross-device | One browser is enough to catch integration breakage; the cost of a matrix is not justified at MVP | — |
| Performance and load | Different tool and different question | — |
| Accessibility conformance | A real audit, not a journey assertion | Acknowledged in `docs/16` |
| Error-path UI behaviour | Valuable, but the journeys are about the happy path staying intact | `docs/16` — future work |

The suite is deliberately **five journeys, not fifty tests**. Each one is a
statement about a capability the brief requires, and a failure names the
capability that broke.

## 2. The five critical journeys

Every journey maps to exactly one frozen capability, so a red journey immediately
names the capability at risk.

| ID | Journey | Capability | What it asserts |
| --- | --- | --- | --- |
| **J1** | The dashboard loads and renders summary indicators, charts and the event table | CRFD-103 | The four KPI tiles carry numeric values; the health pill reports a real state (`UP`/`DEGRADED`, never `UNKNOWN`); the environment badge is populated; both SVG charts render; the source breakdown has rows; the event table has at least one row and the pager reports a total |
| **J2** | A submitted feedback event is accepted and becomes searchable | CRFD-101 / CRFD-102 | The event modal opens; a valid event is submitted; the confirmation **contains an `EVT-` business key**; the modal closes; the new item appears in the table; **the search box is re-populated with that business key**, proving the UI wired the new event into the filter |
| **J3** | Free-text search combined with the status facet returns only matching events | CRFD-102 | Searching a known item id with the `FAILED` facet applied returns rows whose item cell contains the term **and** whose status chip reads `FAILED`; the pager reports a non-zero total |
| **J4** | Drilling into an event shows its state, audit timeline and related alerts | CRFD-104 | Clicking an event row opens the drawer; the header id **starts with `EVT-`**; the status badges render; the timeline has at least three entries; the related exception is `LOW_CONFIDENCE`; the fields panel carries `Correlation id` and `Latency`; closing the drawer actually hides it |
| **J5** | The exception view lists open alerts and an operator can acknowledge one | CRFD-105 | The `OPEN` filter lists alerts; the newest one has a ref, a code and an `OPEN` chip; clicking acknowledge moves it to `ACKNOWLEDGED`; re-filtering by `ACKNOWLEDGED` finds **the same alert by ref** with the new state |

Each journey is a real workflow, not a smoke test: J2 writes data that J3 and J4
then read, and J5 mutates state and verifies the mutation persisted.

## 3. Locator strategy

The single most common way an E2E suite becomes worthless is a locator strategy
that breaks on every styling change. This suite's rules:

1. **`id` for stable landmarks** — `#search-input`, `#btn-add-event`,
   `#events-body`, `#detail-drawer`, `#health-pill`. The application's own ids are
   part of its contract with the test.
2. **`data-*` attributes for identity, never for styling.** Rows carry
   `data-event-id`, alerts carry `data-alert-ref`, and KPI values carry
   `data-value`. These exist so a test can find *the thing it means* rather than
   the fourth `td` in a row.
3. **`[data-action='ack']` for interactive controls** — a control is addressed by
   the action it performs, so moving it in the DOM does not break the test.
4. **CSS structure only where structure is the assertion** — the status chip is
   asserted at `td:nth-child(8) .chip`, because "the status column shows the
   status" is genuinely what is being tested.
5. **Never XPath, never text-only matching.** Text is asserted, not located on.

The `data-*` attributes are the important part. They are a deliberate, documented
part of the markup whose only purpose is to make the test express intent.

## 4. Reading the DOM reliably — the fix that mattered

The suite does not read elements naively. Every read goes through helpers
(`await`, `text`, `fieldOf`, `attribute`, `all`) built on an `await(...)` that:

- polls an explicit condition with a timeout, and
- **tolerates `StaleElementReferenceException` and `NoSuchElementException`**, and
- re-locates the element on the next poll instead of reusing the old reference.

This exists because it was a real bug. The dashboard refreshes its table on a
timer and re-renders after a submit, so an element reference obtained one moment
can be dead the next. The first J2 run failed with exactly that
(`StaleElementReferenceException`, preserved in
`docs/evidence/selenium-defect-crfd-117/`) — a **false** failure that would have
trained everyone to ignore the gate. The full write-up is
`docs/11-continuous-testing-and-defect-fix.md`.

The `fieldOf(alertRef, ".alert-code")` helper is the mature form of the lesson: it
takes an *identity* (the alert ref) and a selector, and resolves them together, so
the element is always re-found within the right item — never a stale handle to a
row that has since moved.

## 5. Two execution modes, one suite

The same suite runs against a local JAR or a containerised application, chosen by
environment so that no test code differs between them.

| Mode | Base URL | Driver | When |
| --- | --- | --- | --- |
| **Local browser** | `http://localhost:18080` | a local Chrome via Selenium Manager / WebDriverManager | developer loop |
| **Remote browser** | `http://crfd-app:8080` | a `selenium/standalone-chrome` container | CI, and the verified container run |

Selecting the remote mode is one variable: `SELENIUM_REMOTE_URL`. Setting it makes
the test support class build a `RemoteWebDriver` pointed at the hub instead of a
local driver.

**The base URL trap.** When the browser runs *inside* the Selenium container, the
base URL must resolve **on the Docker network**, not on the host. `localhost`
inside that container is the browser container itself. That is why the CI run
passes `-Dselenium.baseUrl=http://crfd-app:8080` while the hub is addressed at
`http://crfd-selenium:4444/wd/hub` from the Jenkins container and the browser
reaches the app as `crfd-app:8080` on the shared bridge. Getting this wrong
produces a suite that fails with "connection refused" against an application that
is demonstrably running.

## 6. Running the suite

```bash
# unit + API tests, then package
./mvnw -B clean verify

# add the five journeys against a locally running instance
./mvnw -B -P selenium verify -Dselenium.baseUrl=http://localhost:18080

# the CI shape: app container + browser container on a shared network
export SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub
./mvnw -B -P selenium verify \
    -Dselenium.baseUrl=http://crfd-app:8080 \
    -Dselenium.headless=true \
    -Dselenium.browser=chrome
```

| Property | Default | Meaning |
| --- | --- | --- |
| `selenium.baseUrl` | `http://localhost:8080` | The instance under test |
| `selenium.headless` | `true` | Headless browsing |
| `selenium.browser` | `auto` | `auto` chooses from what is available; `chrome` forces it |
| `SELENIUM_REMOTE_URL` | *(unset)* | Set it to use a remote hub |
| `crfd.screenshots.dir` | `target/selenium-screenshots` | Where failure evidence is written |

The `selenium` Maven profile binds the journeys to **failsafe** in the `verify`
phase, which is the correct place: failsafe is designed for tests that need the
packaged application, and it writes its report even when a test fails. Running
surefire and failsafe in one command is why `mvn -P selenium verify` is the
documented entry point.

## 7. Failure evidence

A failing journey must leave behind everything needed to fix it without being able
to reproduce it. On failure the support class writes three things into
`target/selenium-screenshots/`:

| File | Content |
| --- | --- |
| `<journey>-<timestamp>.png` | A screenshot of the browser at the moment of failure |
| `<journey>-<timestamp>.html` | The full page source |
| `<journey>-<timestamp>.txt` | The URL, the exception class, the WebDriver and Chrome versions, the failing command, the element locator and the session id |

The `.txt` is the file that actually resolves incidents. From the preserved J2
failure, in eleven lines:

```
url=http://host.docker.internal:18080
cause=org.openqa.selenium.StaleElementReferenceException: stale element reference
Element: [...] -> css selector: #events-body tr[data-event-id] td:nth-child(4) div
Session ID: 321d1c0cc5fb231a51a29580ea928b19
```

Chrome version, the exact locator, and the session id — enough to know it was a
read-race rather than a missing feature, without guessing. The pipeline archives
`target/selenium-screenshots/**` on every run, pass or fail, so the evidence
survives the build.

## 8. Integration with the pipeline, and the quality gate

The journeys run in the Jenkins pipeline as **stage 5**, and the ordering is the
point:

```
4  Docker build & tag        ← the image the gate will run
5  Selenium E2E quality gate ← if this fails, stages 6-10 never execute
6  Publish test reports
7  Publish image              ← nothing is published unless the gate passed
8  Deploy fresh container     ← no container is replaced unless the gate passed
```

The gate runs the image built in stage 4 — the *same* artefact stage 8 would
deploy. Gating on one build and shipping another is not a gate.

**A failing journey stops the deployment.** This is demonstrated rather than
asserted: `docs/11` records a defect that turned the gate red (with the failure
screenshot preserved as evidence), the fix, and the green rerun.

The stage also archives `target/app-under-test.log` — the application's own log
from the container the journeys ran against. When a journey fails, having the
server's view of the same thirty seconds is often what turns a mystery into an
obvious cause.

## 9. Known limitations

Stated plainly:

- **Five journeys, not full coverage.** They cover the five critical paths; a
  defect in an untested corner of the UI will not be caught by this suite.
- **No cross-browser matrix.** Chrome only.
- **Timer-driven table refresh is a flakiness source.** The stale-tolerant
  `await()` handles it, but the underlying design (a polling refresh rather than a
  server push) is what creates the race. It is recorded in `docs/16`.
- **The suite mutates state.** J2 creates events and J5 acknowledges an alert, so
  the suite expects a running instance where doing so is acceptable. It is not run
  against production, which is why the pipeline deploys *after* the gate rather
  than gating against the live instance.
- **`PageLoadStrategy.NONE` with explicit readiness polling** is used instead of
  waiting for full page load. It is faster and it removes the "page never finishes
  loading because a background request is slow" failure mode, at the cost of the
  test having to know what "ready" looks like.
