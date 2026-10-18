# 15 — End-to-end walkthrough and viva preparation

Two halves. First, the complete workflow run from one end to the other, with the
commands and the results that were actually observed. Second, the questions this
project invites and the answers that hold up.

---

## Part 1 — The end-to-end workflow

### The chain, in one picture

```text
  a developer writes code
        │
        ▼
  ①  git commit on a feature branch          Conventional Commit, CRFD scope
        │
        ▼
  ②  pull request                            review, verification table, approval
        │
        ▼
  ③  merge --no-ff into develop              the reviewed unit is in the graph
        │
        ▼
  ④  Jenkins: checkout → build → test       32 tests, jacoco.exec
        │
        ▼
  ⑤  package                                 target/*.jar + sha256
        │
        ▼
  ⑥  docker build --build-arg BUILD_REVISION  crfd:v1.0.0-42 / :<sha> / :latest
        │
        ▼
  ⑦  SELENIUM GATE  ── fail ──►  STOP. Nothing published, nothing deployed.
        │ 5/5 pass
        ▼
  ⑧  docker push to the registry              version tag + sha tag
        │
        ▼
  ⑨  deploy: docker rm -f + docker run        a fresh container, gated image,
        │                                      named data volume mounted
        ▼
  ⑩  health + write→read smoke                /api/v1/health, ingest, search, summary
        │
        ▼
  ⑪  Ansible site.yml (twice)                 provisioned; second run changed=0
        │
        ▼
  ⑫  healthcheck / rollback                   UP; previous-release.txt recorded
```

Everything to the right of ⑦ is irreversible, and ⑦ is to their left on purpose. That
single ordering decision is the argument of the whole project, and `docs/13` §3 is
where it is made.

### The walkthrough, with what was observed

Each step below is a command that was run, and the result that came back. Where a
step was not run by Jenkins - because the controller needs a real GitHub remote - that
is said.

**① Commit.**

```bash
$ git log --oneline -1 c97ae88
c97ae88 fix(CRFD-117): mint EVT- prefixed business keys on every write path
```

**②③ Review and merge.** Record: `docs/pull-requests/README.md` §PR #8. The merge was
`--no-ff`, so `git log --first-parent develop` lists it as a reviewed unit.

**④ Build and unit test.**

```bash
$ ./mvnw -B clean test
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0  -- AlertServiceTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0  -- EventServiceTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0  -- EventApiTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**⑤⑥ Container.**

```bash
$ docker build -t crfd:1.0.0 --build-arg APP_VERSION=1.0.0 --build-arg BUILD_REVISION=local .
$ docker images crfd
REPOSITORY   TAG      IMAGE ID       SIZE
crfd         1.0.0    ...            401MB
crfd         latest   ...            401MB
```

**⑦ The gate.**

```bash
$ SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub ./mvnw -B -P selenium \
      failsafe:integration-test failsafe:verify \
      -Dselenium.baseUrl=http://crfd-app:8080 -Dselenium.headless=true
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

And the negative case, which is the one that matters:

```text
docs/evidence/selenium-defect-crfd-117/
  J2_...-20261018-174501.png     the failing screen
  J2_...-20261018-174501.html    the page source at the moment of failure
  J2_...-20261018-174501.txt     the JUnit failure text
```

A gate that has never failed is a script. This one failed a real defect, and the run
that failed published nothing and deployed nothing - because the publish and deploy
stages are to its right.

**⑨⑩ Deploy and verify.**

```bash
$ docker run -d --name crfd-app --restart unless-stopped \
    --network crfd-net --network-alias crfd-live \
    -p 18080:8080 -v crfd-data:/app/crfd-data \
    -e CRFD_ENVIRONMENT=demo crfd:1.0.0
$ curl -fsS http://localhost:18080/api/v1/health
{"status":"UP","checks":{"database":"UP","ingest":"UP",...}}
```

The write→read smoke test and its result, all 11 checks, are in
`docs/evidence/smoke-test.txt`.

**⑪⑫ Provision, check, recover.**

```text
docs/evidence/ansible-summary.txt
  step 1  site.yml -e app_version=0.1.0        ok=30 changed=9  failed=0
  step 2  site.yml -e app_version=0.1.0        ok=30 changed=0  failed=0   ← idempotent
  step 3  deploy.yml -e app_version=1.0.0      ok=20 changed=4  failed=0
  step 4  healthcheck.yml                      ok=7  changed=0  status=UP slo=WITHIN_OBJECTIVE
  step 5  rollback.yml                         ok=23 changed=3  RECOVERED crfd-node -> crfd:0.1.0
```

`changed=0` on step 2 is the single most important line in the evidence set. It is
not a claim that provisioning is idempotent; it is Ansible's own count of the tasks
that did anything, which was none.

### What was *not* run end to end, stated plainly

| Aspect | Status |
| --- | --- |
| Every stage's **command** | Run and captured - build, tests, Selenium, `docker build`, `docker run`, health poll, smoke test, all five playbooks |
| Full **Jenkins-orchestrated** run | **Not executed.** The controller is defined as code and starts; the pipeline needs a GitHub remote this environment does not have |
| Stage **ordering** | Verified by inspection after fixing a real ordering bug (image built after the gate) |
| **Networking** between job containers | Verified independently - the containerised Selenium suite reached the application at `http://crfd-app:8080` |

Every command has been run and its output kept; what has not been demonstrated is
Jenkins *orchestrating* them in one click. Claiming otherwise would be exactly the
kind of overstatement that makes an entire report untrustworthy - and `docs/16`
records it as a limitation rather than burying it here.

### Reproducing the whole thing

```bash
git clone <repo> && cd content-recommendation-feedback-dashboard

# 1. build and unit-test
./mvnw -B clean package                              # 32 tests

# 2. run it
java -jar target/*.jar &                             # :8080
scripts/smoke-test.sh http://localhost:8080          # 11 checks

# 3. container
docker build -t crfd:1.0.0 .
docker run -d --name crfd-app -p 18080:8080 -v crfd-data:/app/crfd-data crfd:1.0.0
scripts/smoke-test.sh http://localhost:18080

# 4. the Selenium gate
docker run -d --name crfd-selenium -p 4444:4444 --network crfd-net \
    selenium/standalone-chrome:4.25.0
SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub ./mvnw -B -P selenium verify \
    -Dselenium.baseUrl=http://crfd-app:8080

# 5. the pipeline, controller as code
docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d     # :8085

# 6. provisioning, five playbooks, evidence in docs/evidence/
scripts/ansible-demo.sh
```

Full prerequisites and per-step detail: `docs/18-local-runbook.md`.

---

## Part 2 — Viva preparation

The questions below are grouped where they naturally arise. The answers are the ones
this project can actually defend, which means several of them admit a limitation.

### Architecture and scope

**Q. Justify the frozen scope. What did you leave out, and why?**

Six capabilities, frozen in week 1: event entry, searchable dashboard, summary
indicators, status drill-down, alert/exception view, and explicit feedback. Left out
deliberately: real-time streaming ingestion, multi-tenancy, a recommendation model
(this dashboard observes a recommendation pipeline, it is not one), authentication,
and alerting integrations. The exclusion that needed an argument was explicit
feedback: the brief's capability list mentions "data/event entry ... alert/exception
view" and feedback capture is arguably implied by "content recommendation feedback
dashboard". It was added as `CRFD-106` with a change request rather than smuggled in,
because a frozen scope that quietly grows is not frozen.

**Q. Why Spring Boot and an embedded container when the brief mentions Tomcat?**

Because the deployment target is a container, and a fat JAR with embedded Tomcat is
the artefact a container wants: one file, one process, no external container to
manage. The brief's Tomcat requirement is met two ways - embedded Tomcat as the
default, and a `war` Maven profile producing `target/crfd.war` for an external Tomcat
10.1, with Nginx in front either way. Choosing embedded for the default and keeping
the external path buildable is a decision that is documented (`docs/03`, `docs/09`)
rather than assumed.

**Q. Where is the Nginx layer, and what does it do?**

It terminates on `:80`, proxies `/` to the application, exposes `/healthz`, and holds
the app behind `depends_on: service_healthy` - so it cannot come up in front of an
application that is not ready. Verified in the Compose stack.

### Data and the API

**Q. An event arrives with no `itemId`. What happens?**

It is **quarantined**, not rejected and not discarded: the row is stored with
`QUARANTINED` status and a `SCHEMA_MISMATCH` alert is raised. Rejecting it would lose
data, and discarding it would lose it silently - which is worse than losing it
loudly, because a silent loss is a bug report you never receive. The `CRFD-101` tests
cover it.

**Q. Why are events and feedback treated differently on repeat submission?**

Events are idempotent: the same `eventId` twice is rejected with 409 and a
`DUPLICATE_EVENT` alert, because an event is a fact and a fact repeated is a replay.
Feedback is *not* idempotent: the newest rating wins, because a user changing their
mind is not a duplicate. Recording the first rating and refusing the second would
store an opinion the user has already abandoned. That distinction was argued in PR #6.

### CI/CD

**Q. A test fails in the pipeline. What happens next?**

Nothing is published and nothing is deployed. In the Jenkins pipeline the Selenium gate
is **stage 5 of 10**, and the image publish and the deployment are stages 7 and 8; in
the workflow diagram above they are steps ⑦, ⑧ and ⑨. This was demonstrated rather than
asserted: `docs/evidence/selenium-defect-crfd-117/` is the failing run, and it produced
no image and no deployment.

**Q. Why is the image built *before* the gate?**

It was not, in the first draft, and that was the bug. The gate tried to run an image
that did not exist. The fix was not merely to reorder the stages - it was to see *why*
the order was wrong: a gate that validates one artefact while a different artefact
ships is theatre. It consumes the time of running the tests and returns none of the
confidence. The image is now built once and referenced by one tag across build, gate,
publish and deploy (`docs/13` §3).

**Q. Jenkins is a container. How does it reach the application it deploys?**

By network alias on a pinned network, never `localhost`. Jenkins uses the host Docker
socket, so a container it starts is a *sibling*; `localhost` inside a build step is
Jenkins itself. Everything is on `crfd-ci-net` and addressed as `crfd-app`,
`crfd-selenium` or `crfd-live`. Host port publishing exists only for humans.

**Q. What is the parameterised environment setting the brief asks for?**

Several, all pipeline parameters: `ENVIRONMENT`, `APP_PORT`, `CRFD_LOW_CONFIDENCE`,
`CRFD_CRITICAL_LATENCY_MS`, `IMAGE_TAG`, `REGISTRY`. The same image alerts on
different thresholds per environment, which is the clearest demonstration that the
configuration is genuinely externalised rather than baked in. The pipeline also
enforces `CRFD_SIMULATION_ENABLED=false` and `CRFD_SEED_ON_STARTUP=false` for
`production` - synthetic traffic and a seeded database are what make a demo pleasant
and a production deployment wrong.

### Selenium

**Q. How many journeys, and what makes them "critical"?**

Five, chosen to cover the capabilities rather than the code: (J1) the dashboard loads
and shows indicators; (J2) a feedback event is submitted and becomes searchable;
(J3) the exception queue raises and a breach is acknowledged; (J4) the drill-down
shows an event's audit timeline; (J5) an invalid submission is rejected with a
readable message. Each one fails if a whole capability is broken, which is what makes
it worth the runtime.

**Q. Your suite flaked. What did you do?**

Found the cause rather than the symptom. The table auto-refreshes, so a read could
lose its element between locating it and reading it, raising
`StaleElementReferenceException` intermittently. The fix re-locates and retries on
that exception - a longer sleep would have masked it and slowed every run. Making the
test *reliable* then exposed the real defect (`CRFD-117`), because a deterministic
test that fails every time is a bug report and a flaky test that fails one time in
five is a shrug.

**Q. How do you know your Selenium tests can fail?**

Because they were run against the pre-fix tree and four regression tests failed:
`docs/evidence/regression-tests-fail-before-fix.txt`. That is the only property that
makes a test worth keeping.

### Docker

**Q. Why multi-stage, and what does it buy?**

The build stage has the JDK and the Maven cache; the runtime stage has a JRE. The
final image is 401 MB instead of close to a gigabyte, and - more importantly - nothing
at runtime can compile Java, so a compromised container cannot fetch and build
attacker-supplied code with the shipped toolchain.

**Q. Your deploy removes the container and starts a new one. Isn't that data loss?**

Only if the volume is not mounted, and that is a real trap this project hit. The image
declares `VOLUME /app/crfd-data`, so `docker run` without `-v` creates an *anonymous*
volume - which `docker rm` then destroys. Replace-and-run would have wiped event
history on every deployment. Both the pipeline and the Ansible role mount the named
volume `crfd-data` explicitly. It is recorded in PR #12 because it is the kind of
thing that is obvious in hindsight and invisible at the time.

### Provisioning

**Q. Puppet or Ansible, and why?**

Ansible. Both were permitted. The deciding factor is that the project's central claim
is "here is a clean node, now watch it be provisioned" - and Ansible is agentless, so
the node genuinely starts with SSH and nothing else. Puppet's continuous convergence
is better at fleet scale and it would need an agent installed first, which weakens the
demonstration. The trade-off is stated honestly in `docs/14` §9 rather than presented
as a clear win.

**Q. Prove idempotency.**

The second identical run reports `changed=0`. Not a claim - Ansible's own count of the
tasks that did anything, and the answer is none:
`docs/evidence/ansible-02-idempotency-second-run.log`. The mechanism that makes it
work is that the rollback record is written *only when the version changes*; a task
that rewrites a file on every run can never be idempotent (`docs/14` §4).

**Q. What does rollback do, and what happens if the rollback itself breaks?**

`rollback.yml` restores the release named in `/etc/crfd/previous-release.txt`, and
that file is written from the release being *left*. So after a rollback the node can
roll *forward* again - recovery is symmetric. The evidence shows both transitions in
`release-history.log`. A rollback that leaves a node unable to move forward is a trap
dressed as a fix; this design came out of a reviewer's question (PR #17).

### The two design corrections

These are worth offering before being asked, because they are the most interesting
things in the project.

**Q. Which mistake did you make twice?**

Letting a business metric control availability. `GET /api/v1/health` first returned
503 whenever anything was off - including a pipeline success rate below its objective.
Seeding the database produces about 0.90 against a 0.95 objective, so a fully
functional instance reported unhealthy; the container health check failed, Nginx never
started because of `depends_on: service_healthy`, and the Ansible wait timed out and
failed a good deployment. Split into readiness (`/health`) and the business gate
(`/health/slo`). Then `healthcheck.yml` made **the same mistake** and asserted `UP`
only, failing on `DEGRADED`. The rule the project now states as a design principle:
*never let a business metric decide whether a node is able to serve traffic.*

**Q. Why does `/health` return 200 for `DEGRADED`?**

Because `DEGRADED` answers a business question, and the endpoint answers an
availability question. Returning 503 for it is how a reporting number takes a working
node out of a load balancer - which is precisely what happened here, twice. The
distinction is now in the endpoint documentation, the Ansible health check, the
container `HEALTHCHECK` and the Jenkins deploy gate.

### Git

**Q. Why `--no-ff` instead of squash or fast-forward?**

Because `git log --first-parent develop` then reads as the list of reviewed units -
19 merge commits naming the PR and the branch. Squash loses the intermediate commits,
so a `git bisect` lands on one enormous commit and a review cannot be reconstructed
from the history; fast-forward makes the branch vanish from the graph. `--no-ff`
preserves both views at the cost of some graph tidiness, which is the right trade.

**Q. Walk me through the merge conflict.**

Two branches cut from the same tip, both editing the same comments above
`crfd.thresholds`. `UU src/main/resources/application.yml`. The two sides were read
for intent and found to be complementary, not contradictory - one says why thresholds
are overridable, the other says what a breach does - so both were kept and rewritten
into one statement rather than concatenated. The suite was re-run because a resolution
is code nobody wrote: 31 green. Both parents are recorded in the merge commit, so the
history shows a real merge. Full account and evidence: `docs/07` §6.

**Q. What is dishonest in this repository?**

Two things, both stated rather than hidden: the commit dates are all from one
reconstruction session rather than spread across a 15-week calendar, and the reviewer
comments were written by the same author taking the reviewer's position. What is
genuine and checkable: every diff (including the defects, which really were
reintroduced and really did fail the tests), every branch, every merge commit's
parents, the conflict, the tags, and the regression tests failing against the pre-fix
tree. `docs/06` §9 and `docs/16` both say so.

### Limitations and future work

**Q. What would you do next, with another four weeks?**

In priority order: (1) a real GitHub remote with branch protection and required status
checks, so the pipeline gate is enforced by the server and not only by the
`Jenkinsfile`; (2) an externalised database - MySQL is already a profile, but schema
migration with Flyway and a connection-pool story under load are not done; (3) the
alert suppression refinement the PR #4 review asked for, where a worsening incident
re-raises; (4) multi-node provisioning to exercise `serial: 1` and
`max_fail_percentage` for real, rather than on a fleet of one; (5) a load test to make
the p95 and throughput figures meaningful.

**Q. What is the weakest part of this deliverable?**

The full Jenkins pipeline was never executed end to end by Jenkins, because the
controller needs a GitHub remote this environment does not have. Every stage command
was run and captured individually, and the ordering was verified by inspection, but
"Jenkins orchestrated it in one click" is not something this project can claim.
`docs/16` lists it first.

---

## Part 3 — Presentation outline

A 15-minute run, with backups if the demos are not available live:

| # | Slide | Point | Backed by |
| --- | --- | --- | --- |
| 1 | The problem | Recommendation quality is invisible without feedback data | `docs/01` |
| 2 | Frozen scope | Six capabilities, one change request, stated | `docs/01` §scope |
| 3 | Architecture | Browser → Nginx → Spring Boot → H2/MySQL, one artefact | `docs/03` |
| 4 | The API | One uniform contract, uniform errors | `docs/05` |
| 5 | **Live**: the dashboard | Ingest an event, find it, drill in, see the audit trail | the running app |
| 6 | Git | 19 reviewed PRs, 2 tags, one conflict resolved | `docs/06`, `docs/07` |
| 7 | **Live**: the conflict | `docs/evidence/merge-conflict/` and the resolution | evidence file |
| 8 | Jenkins | 10 pipeline stages; the gate is stage 5, the deploy is stage 8 | `docs/13` §3 |
| 9 | Selenium | Five journeys; the defect they caught | `docs/11`, evidence |
| 10 | **Live**: the gate failing | The screenshot and page source from the failing run | `selenium-defect-crfd-117/` |
| 11 | Docker | Versioned tags, `BUILD_REVISION` in the image, fresh deploy | `docs/12`, `docs/13` |
| 12 | Ansible | `changed=0` on the second run | `docs/14`, evidence |
| 13 | The two corrections | Readiness vs SLO; the anonymous volume | `docs/11`, PR #10, PR #12 |
| 14 | Limitations | Said first, not asked for | `docs/16` |

The three live demos are ordered so that the two that can fail loudest - the gate and
the conflict - have the evidence files behind them. A demo that depends on a running
container is a demo that stops working forty seconds before the viva.
