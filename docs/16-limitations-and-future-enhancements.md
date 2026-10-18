# 16 — Limitations and future enhancements

A report that claims no limitations is not credible, and one that hides them in the
middle of other chapters cannot be audited. So every known limitation is collected
here, with its impact and what would remove it, and the other chapters point at this
one rather than repeating it.

They are grouped by how much they matter to the *verdict* on the work:

- **Part A — limitations of the exercise.** Things about how this deliverable was
  produced that a reader must know to judge it fairly. These are the ones that would
  change a grade.
- **Part B — technical limitations of the product.** Real engineering gaps, scoped out
  deliberately or not yet done.
- **Part C — future enhancements**, prioritised, with the limitation each one closes.

---

## Part A — Limitations of the exercise

### A1. The git history is a reconstruction, not a 15-week log

**What.** Every commit is dated within the same day. The application, the tests, the
container, the pipeline and the playbooks were built and verified first as a working
system; the history was then laid down against the finished tree, branch by branch,
with the defects deliberately reintroduced and fixed on their own branches.

**Impact.** `git log --date=iso` does not read like a 15-week project. A reader
expecting an incremental history will notice within one command.

**What is still genuine, and checkable:**

- every commit's **diff** - `git show c97ae88` is a real repair, and its parent is the
  code that produced the failing Selenium run in `docs/evidence/selenium-defect-crfd-117/`;
- every **branch** and merge commit, with both parents recorded;
- the **conflict**, reproduced with its markers captured;
- the **tags**, and that the tagged tree is the tree the suite was run against;
- the **regression tests failing against the pre-fix tree**
  (`docs/evidence/regression-tests-fail-before-fix.txt`). If the "before" code were not
  genuinely broken, those four tests would pass - this is the strongest single check on
  the whole story.

**Why not fabricate the dates.** Spreading 48 commits over 15 weeks would have
produced a history that *looked* like a project artefact without being one. A
compressed timeline honestly labelled is worth more than a plausible timeline that is
invented - particularly in a report whose central claim is that its evidence can be
trusted. Same reasoning, stated again, in `docs/06` §9.

**What would remove it.** Committing as work happens. Nothing else.

---

### A2. There is no GitHub remote, so the pull requests are recorded rather than opened

**What.** The environment has no GitHub account and no `gh`. `origin` is a local bare
repository (`crfd-origin.git`) used to verify that pushes, branch tracking and tag
pushes work. The 19 PRs are recorded in `docs/pull-requests/README.md`, with the
template body filled in and a review comment each.

**Impact.** Branch protection, required status checks, and a CI status on the PR are
absent. Nothing *enforced* the rule that `develop` only receives reviewed, green
merges - it was a convention, held by the one person doing the work.

**What is still genuine.** The merge commits are real `git merge --no-ff` commits
naming the PR and the branch, so `git log --merges --first-parent develop`
reproduces the review list, and the verification table in each record is drawn from
commands that were actually run.

**What would remove it.** Push to GitHub and open the PRs; the branch model, the commit
convention and the merge strategy were chosen so that none of them changes. The
substitution table is in `docs/06` §11.

---

### A3. The Jenkins pipeline was never run end to end by Jenkins

**What.** The controller is defined as code (`infra/jenkins/`) and starts. Every stage
command in the `Jenkinsfile` was run and its output captured - `mvn clean test`,
`mvn -P selenium verify`, `docker build`, `docker run`, the health poll, the write→read
smoke test, all five Ansible playbooks. What was never done is Jenkins *orchestrating*
them in one run, because the pipeline's checkout stage needs a real remote.

**Impact.** This is the largest gap in the deliverable. It is the difference between
"the pipeline is correct" and "the pipeline ran". Arguments about stage ordering were
settled by inspection and by running the stages separately, not by a green build page.

**Honest mitigation.** The stage ordering was verified as a *property* - nothing
irreversible occurs before everything verifiable has passed - and that property does
not depend on Jenkins having run. The one ordering bug that was found (the image built
after the gate) was found by reading, not by running, which is itself evidence that
reading was not enough and that a real run is missing.

**What would remove it.** A GitHub remote and a `Jenkinsfile`-based multibranch job.
`docs/13` §9 states the same limitation.

---

### A4. The reviewer and the author are the same person

**What.** The review comments in `docs/pull-requests/README.md` were written by the
author taking the reviewer's position.

**Impact.** Reviews are the strongest defect filter in software, and this project
substitutes introspection for them. Seven of the nineteen asked for a real change
(bounded paging, the readiness split, the anonymous-volume trap, re-locate instead of
wait longer), so the *habit* of review is visible - but a second reader with different
assumptions is exactly what a solo effort cannot supply. The `EVT-` defect in `CRFD-117`
is the sharpest illustration: the defect survived 31 tests written by the same person
who wrote the code, and was found by an automated journey instead.

**What would remove it.** A second person. Failing that, the automated gates are the
substitute - which is the same conclusion, arrived at from the other direction.

---

### A5. One node, one environment, one host

**What.** The Ansible work targets a single disposable node; there is no staging
environment distinct from the demo, no cluster, no multi-node rollout.

**Impact.** The rolling-deployment machinery (`serial: 1`, `max_fail_percentage: 0`) is
written and correct but exercises a fleet of one, so the interesting failure mode - a
node failing mid-rollout - is not demonstrated. "No downtime during deploy" is
unproven because nothing had to stay up.

**What would remove it.** Two or three nodes and a real rolling play.

---

### A6. The demo's provisioning target is a nested Docker engine

**What.** The clean node is a `docker:dind` container. A nested engine cannot apply
cgroup controller configuration, so the container resource limits (`app_memory_limit`,
`app_cpu_limit`) are opt-in and blanked for the demo via
`inventory/demo-overrides.yml`. The production defaults keep `768m` / `1.0`.

**Impact.** The resource-limit tasks are not exercised. They are correct for a real
node and unproven on the demo one.

**Secondary impact.** The demo node authenticates with a password (`sshpass`) because
that is what a throwaway `dind` container needs; `hosts.example.ini` shows the key-based
shape a real inventory would use, but the key path is not demonstrated.

**What would remove it.** A VM or systemd-enabled node, or a privileged node with
cgroup delegation configured.

---

### A7. No load, scale or soak testing

**What.** The database holds seeded demonstration volume - hundreds of events. Nothing
was run at volume, and nothing ran for long enough to expose a leak or a slow query.

**Impact.** Every statement about performance is a statement about *design*, not
measurement: aggregate queries rather than in-memory counting, bounded paging, a capped
export, and a p95 computed in SQL. Those decisions are sound; the numbers behind them
are not measured. The p95 and throughput figures on the dashboard are therefore
meaningful as a demonstration and not as a capacity plan.

**What would remove it.** A load generator (Gatling, k6) with a realistic event mix, and
a soak run of several hours.

---

### A8. No security review beyond configuration hygiene

**What.** Some real things are done: the image runs as a non-root user, no secret is in
the repository, Jenkins credentials are referenced by id and injected at runtime, and
the traffic simulator is off in production so it fails closed.

**Impact.** There has been no threat modelling, no dependency vulnerability scan, no
penetration test, no OWASP review, and no authentication or authorisation on the API at
all. Anyone who can reach the port can read every event and acknowledge every alert.

**What would remove it.** Authentication and role separation first (the API is
currently entirely open), then a dependency scan in the pipeline
(`dependency-check` or Trivy on the image), then a review against the OWASP API Top 10.

---

### A9. No lint or policy enforcement on the infrastructure code

**What.** The playbooks and the `Jenkinsfile` are reviewed and exercised, but not
lint-enforced. There is no `ansible-lint`, no `molecule`, no `.editorconfig`, no
pre-commit hook, no `commitlint` for the commit messages.

**Impact.** The conventions in `BRANCHING.md` are held by discipline. A commit with a
malformed subject, or a task with a subtly wrong mode, would not be caught by anything
but a reader.

**What would remove it.** `commitlint` on a pre-commit hook and in CI; `ansible-lint`
and `molecule` in the pipeline; the GitHub Actions workflow already exists
(`.github/workflows/ci.yml`) and is the natural place to add them.

---

## Part B — Technical limitations of the product

### B1. H2 in file mode is single-writer

**What.** The default database is H2 in file mode. It permits one writer.

**Impact.** Fine for a demonstration and for the tests. Two application instances
against one file is a corrupted database, not a slow one. The Compose deployment is
therefore a single instance by construction, and "scale the app horizontally" is not
available without changing the database.

**Mitigation already present.** MySQL is a first-class profile
(`application-mysql.yml`, `docker-compose.mysql.yml`) and the `mysql` Maven profile
exists, so the schema and the queries were written to be portable.

**What would remove it.** MySQL (or PostgreSQL) as the default for anything deployed
more than once, plus a connection-pool and read-replica story.

### B2. No schema migration tool

**What.** `ddl-auto: update` and a reference `db/schema.sql`. There is no Flyway or
Liquibase.

**Impact.** `update` is convenient and unacceptable in a real deployment: it never
drops a column, cannot express a data migration, and gives no history of what changed
when. A `previous-release.txt`-style rollback of the *application* can therefore leave
the *schema* ahead of it - the two are not versioned together.

**What would remove it.** Flyway with versioned migrations, and a migration step that
runs before the new container is started.

### B3. `%` and `_` in a search term act as wildcards

**What.** The free-text search passes the term into a `LIKE`. `%` and `_` therefore
have their SQL meaning.

**Impact.** Mostly harmless and occasionally useful for a power user. The real issue is
that it makes the API's behaviour depend on SQL wildcard semantics rather than on a
search contract - the same query means different things on H2 and MySQL for other
special characters.

**What would remove it.** Escape the wildcards and, better, decide deliberately whether
the search is prefix, substring or token-based, and document it.

### B4. p95 latency is computed in SQL and is not portable

**What.** The percentile is calculated in the database. H2 and MySQL 8 both support a
percentile, through different syntax.

**Impact.** The query works on the two databases in use and would need rewriting for a
third. It is a subtle portability trap - it fails at deploy time, not at compile time.

**What would remove it.** Compute the percentile in Java over a bounded, appropriately
indexed window, or pull it into a database-specific query fragment behind a port.

### B5. Alert suppression can hide a worsening incident

**What.** A new `OPEN` alert is suppressed when one already exists for the same
`(code, eventRef)`. The occurrence count is incremented.

**Impact.** An incident that is twenty times worse raises the same single alert. The
count is in the payload, so the information is not lost, but nothing acts on it - no
re-raise, no threshold escalation, no notification.

**What would remove it.** Re-raise (or escalate severity) when the occurrence count or
the rate crosses a further threshold. Small change, deliberately not in scope.

### B6. Feedback is not idempotent

**What.** `POST /api/v1/feedback` accepts repeated submissions and the newest wins.

**Impact.** This is a *chosen* behaviour (PR #6) - a user may change their mind - but it
means a client that retries a submit after a timeout creates a duplicate rating and the
"current view" is now whichever arrived last.

**What would remove it.** A client-supplied idempotency key on the feedback endpoint,
preserving the "newest wins" semantics for genuine changes while making a retry safe.

### B7. No authentication, authorisation or audit of the operators

**What.** Every endpoint is open. `PATCH /events/{id}/status` records `changedBy` as a
free-text string supplied by the caller.

**Impact.** The audit trail is complete about *what* changed and unreliable about *who*
changed it, because the caller names itself. Alert acknowledgement has the same problem.
For a dashboard whose purpose is operator accountability, this is the most important
functional gap.

**What would remove it.** Authentication, with the operator identity taken from the
authenticated principal rather than from the request body.

### B8. The Selenium browser is a third-party image and the journeys are English-only

**What.** `selenium/standalone-chrome:4.25.0`, pinned but external. The journeys assert
on English strings in a handful of places and on `data-*` attributes in the rest.

**Impact.** The pinned tag means a base-image CVE requires a deliberate bump, and a UI
translation would break the string assertions. The `data-*` strategy was adopted
precisely to keep most of the suite immune to wording, so the exposure is limited to the
few assertions about user-facing messages - which is deliberate, because "the user sees a
readable error" is worth asserting.

**What would remove it.** A locally built browser image, and `data-*` hooks (or message
ids) for the remaining assertions.

### B9. Accessibility is not verified

**What.** Semantic landmarks, labelled controls and keyboard-operable dialogs were part
of the UI work, but there is no automated accessibility check and no manual screen-reader
pass.

**Impact.** "Accessible" is a claim made from markup and not from test results. The one
thing genuinely verified is what the Selenium journeys exercise: landmarks exist, the
table is populated, the drawer opens.

**What would remove it.** axe-core in the Selenium suite for the automated part - cheap,
and it would catch the regressions that matter - plus a manual pass.

---

## Part C — Future enhancements, prioritised

Ordered by (value to the business) ÷ (effort), with the limitation each one closes.

| # | Enhancement | Closes | Effort |
| --- | --- | --- | --- |
| 1 | **Push to GitHub; branch protection with required checks and review** | A2, A3 | S |
| 2 | **Authentication and operator identity from the principal** | A8, B7 | M |
| 3 | **Flyway migrations, run before the new container starts** | B2 | M |
| 4 | **MySQL as the deployed default** | B1 | S |
| 5 | **`ansible-lint` + `molecule` in the pipeline** | A9 | S |
| 6 | **Alert escalation on occurrence rate** | B5 | S |
| 7 | **axe-core accessibility checks inside the Selenium suite** | B9 | S |
| 8 | **Idempotency key on feedback** | B6 | S |
| 9 | **Load test with k6 or Gatling; publish the results as evidence** | A7 | M |
| 10 | **Three-node rolling deploy to exercise `serial: 1`** | A5 | M |
| 11 | **SLO dashboards from `/health/slo`, and page on a *rate* not a state** | B7-adjacent | M |
| 12 | **Zero-downtime deploy behind Nginx (blue/green or two-container)** | A5 | L |
| 13 | **Token-based search with a documented contract** | B3 | M |
| 14 | **Percentile computed behind a port, or in Java over a bounded window** | B4 | S |
| 15 | **A locally built browser image** | B8 | S |

Where "S" is a day or less, "M" is up to a week, and "L" is longer - estimates from the
sprint calibration in `docs/02`, offered as such and not as quotes.

### What is deliberately *not* on the list

- **A recommendation model.** This dashboard observes a recommendation pipeline; it is
  not one. Adding the model would change what the project is, not extend it.
- **Multi-tenancy.** Nothing in the problem definition needs it, and it would touch every
  table and every query for no demonstrated benefit.
- **A message queue for ingestion.** The synchronous path is adequate for the scope and
  is *testable deterministically* because it is synchronous - which is why
  `crfd.processing.async-enabled=false` gives the same behaviour in tests as production.
  A queue would be the right answer at a throughput this project has not measured
  (see A7).

That last point is the honest version of "we would scale it": the answer to "how would
this handle 100× the traffic?" is currently *we have not measured it, here is where the
numbers would come from, and here are the three decisions already taken so that the
database is not the first thing to fall over.*

---

## Summary

The deliverable is strongest where it was verifiable - the defect repairs, the
idempotency, the gate ordering, the conflict resolution - and weakest where verification
needs resources it did not have: a second reviewer (A4), a real Jenkins run (A3), real
scale (A5, A7), and real adversaries (A8).

The one thing worth naming explicitly is that the project's own best evidence is a
*negative* result: four regression tests failing against the pre-fix tree
(`docs/evidence/regression-tests-fail-before-fix.txt`). That file proves the tests can
fail; without it, every claim of the form "the tests verify this" would be unfalsifiable.
