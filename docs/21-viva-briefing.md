# Viva briefing — what the project is, how Jenkins is wired, what to show

This is the operator's sheet for the demonstration: the story, the click path, the
numbers to quote, and the answers to the questions that usually follow. It is
written to be read the night before and glanced at during the viva.

Companion documents:

| Document | Use |
| --- | --- |
| `docs/15-final-end-to-end-and-viva.md` | The formal end-to-end chapter (long form) |
| `docs/19-lab-explanation-and-demo-guide.docx` / `.pdf` | The submitted lab explanation |
| `docs/20-docker-dataset-and-database-tour.md` | The Docker/dataset/database walkthrough in detail |
| `docs/17-troubleshooting-guide.md` | Every failure that was hit and how it was fixed |
| `docs/evidence/` | Screenshots, logs, JUnit and console excerpts quoted here |

---

## 1. One-page cheat sheet

Open these before anyone walks in.

| Thing | Where | Credentials |
| --- | --- | --- |
| Dashboard | <http://localhost:8080/> | none |
| Swagger UI (API catalogue) | <http://localhost:8080/swagger-ui/index.html> | none |
| H2 database console | <http://localhost:8080/h2-console/> | JDBC `jdbc:h2:file:/app/crfd-data/crfd`, user `sa`, password empty |
| Jenkins | <http://localhost:8085/> | **no login** (security deliberately off) |
| Jenkins job | <http://localhost:8085/job/crfd-dashboard-pipeline/> | none |
| Selenium (noVNC) | <http://localhost:4444/> / <http://localhost:7900/> | no password |
| Local registry | <http://localhost:5001/v2/_catalog> | none |
| Docker Desktop | Start menu → Docker Desktop → **Containers** | - |

Ports:

| Port | Owner |
| --- | --- |
| 8080 | `crfd-app` — the container Jenkins deployed |
| 8081 | Nginx from `docker compose` (only when that stack is running) |
| 8085 | `crfd-jenkins` controller |
| 5001 | `crfd-registry` |
| 4444 / 7900 | `crfd-selenium-standalone` (WebDriver / noVNC) |
| 50000 | Jenkins inbound agents |
| 18080 / 18090 / 3306 / 80 | alternative deploy port, external Tomcat, MySQL profile, Nginx in Compose |

---

## 2. The 60-second pitch (say this first)

> A content platform recommends items to users. When those recommendations go
> stale, the drop is silent — nobody sees it until revenue falls. This project is
> the observability and feedback loop for that: recommendation events come in,
> the system validates and classifies them, the dashboard shows whether the
> pipeline is healthy, which items are being dismissed, and which events failed
> or were quarantined — with an alert queue an operator can work through.
>
> Around that product there is a full DevOps path: Git and pull requests, a
> Jenkins pipeline that builds and tests on every commit, a Selenium suite that
> gates the deployment, a Docker image that is versioned per build, and an
> Ansible playbook that provisions a clean node and can roll back. Everything in
> the demo is reproducible from source control — no hand-clicked setup.

If asked "what are we *trying* to do": **make recommendation quality measurable
and make a bad release impossible to ship silently.**

---

## 3. What the product does (the six MVP capabilities)

| # | Capability | Ticket | Where to see it |
| --- | --- | --- | --- |
| 1 | Data / event entry — single, bulk, validated | CRFD-101 | **+ Add event**, `POST /api/v1/events`, `/bulk` |
| 2 | Searchable dashboard — filters, facets, paging, CSV | CRFD-102 | search box, status/source/type facets, **Export CSV** |
| 3 | Summary indicators — events, success rate, CTR, latency | CRFD-103 | the KPI cards at the top |
| 4 | Status drill-down — one event, its state and audit trail | CRFD-104 | click any row → detail panel |
| 5 | Alert / exception view — open alerts, acknowledge, resolve | CRFD-105 | **Exceptions** tab |
| 6 | Explicit feedback — rating, reason, comment | CRFD-106 | feedback form / `POST /api/v1/feedback` |

Two design decisions worth saying out loud, because they are the interesting part:

- **Two layers of validation.** A malformed payload is rejected with `400` at the
  edge. A well-formed but semantically unusable event (no `itemId`) is *accepted*,
  marked `QUARANTINED`, and raises a `SCHEMA_MISMATCH` alert. The system shows the
  exception instead of silently dropping traffic.
- **Readiness is not the SLO.** `/api/v1/health` returns `200` for `UP` and
  `DEGRADED` (the process can serve traffic), and `503` only when it is `DOWN`.
  `/api/v1/health/slo` returns `503` when the business objective is breached
  (success rate below 95%). An orchestrator should only restart on the first; a
  dashboard should page on the second.

---

## 4. The DevOps chain, and what proves each link

| Stage of the lifecycle | Tool | What was actually done | Evidence |
| --- | --- | --- | --- |
| Plan | Agile backlog, 15-week plan | 6 MVP stories frozen, sprint plan, Definition of Done | `docs/02-agile-plan.md` |
| Code | Git + GitHub flow | feature branches, conventional commits, PR review, a conflict created and resolved, tags | `docs/06-git-and-github-workflow.md`, `docs/evidence/merge-conflict/` |
| Build | Maven wrapper, JDK 21 | fat JAR, `war` profile, reproducible build | `pom.xml`, `mvnw` |
| Test | JUnit 5 + Selenium 4 | 37 tests per pipeline run; 3–5 critical journeys in a real browser | `docs/10-selenium-test-plan.md`, build #13 test report |
| Release | Docker | multi-stage image, versioned per build, immutable tags | `Dockerfile`, `crfd:v3.3.5-13` |
| Deploy | Jenkins + Docker | pipeline builds, gates, then deploys a fresh container | `Jenkinsfile`, `docs/09-pipeline-and-deployment.md` |
| Provision | Ansible | clean node, idempotent second run, health check, rollback | `docs/14-ansible-provisioning.md`, `docs/evidence/ansible-0*.log` |
| Operate / monitor | Health, metrics, alerts | readiness + SLO endpoints, alert lifecycle, audit trail | `docs/03`, `docs/05` |

---

## 5. How Jenkins is set up (the part examiners probe)

### 5.1 Architecture

Jenkins runs as a **container**, not as a host install:

```
Windows host
└── Docker Desktop (Linux VM)
    ├── crfd-jenkins            jenkins/jenkins:lts-jdk21      :8085 → 8080
    │     ├── /var/jenkins_home          (volume jenkins_jenkins-home)
    │     ├── /var/run/docker.sock       (host socket → Docker-outside-of-Docker)
    │     └── /workspace/crfd            (the repository, bind mount)
    ├── crfd-selenium-standalone selenium/standalone-chrome    :4444, :7900
    ├── crfd-registry           registry:2                     :5001
    └── crfd-app                crfd:v3.3.5-13                 :8080
        all four on network crfd-ci-net
```

The controller is given the host's Docker socket, so the pipeline does not run
Docker-in-Docker — it asks the **host daemon** to build and run containers. Those
containers are *siblings* of Jenkins, not children.

That single fact explains the most common bug in this setup: **inside the
Jenkins container, `localhost` is Jenkins itself**, not the application. The
pipeline must therefore address the app as `crfd-app:8080` or by the alias
`crfd-live:8080` on the shared network `crfd-ci-net`. Selenium does the same.

### 5.2 Provisioned as code, not clicked

| File | What it declares |
| --- | --- |
| `infra/jenkins/docker-compose.jenkins.yml` | controller, Selenium, registry; ports; volumes; socket; network `crfd-ci-net` |
| `infra/jenkins/plugins.txt` | every plugin the Jenkinsfile needs |
| `infra/jenkins/jenkins.yaml` | JCasC: system settings, JDK 21 at `/opt/java/openjdk`, Maven 3.9.9, credentials placeholders, and the **seed job** |
| `Jenkinsfile` | the pipeline itself — the job's definition lives in Git, not in Jenkins |

The seed job creates `crfd-dashboard-pipeline` from the repository (`develop`,
`scriptPath Jenkinsfile`, `lightweight(false)` so parameters are read from the
file). Change the pipeline ⇒ change the Jenkinsfile ⇒ commit. Nothing is
configured by hand.

Two traps that were found and are documented in the troubleshooting guide:

- A withdrawn plugin name (`jdks`, `cloudbees-docker-custom-build-environment`)
  makes the plugin installer abandon the **whole** list — the controller came up
  with no plugins at all. Both were removed and the JDK is declared by its local
  home instead.
- JCasC treats an unknown attribute as a **fatal boot error**. Five obsolete
  attributes were removed.

### 5.3 The pipeline: 10 stages

| # | Stage | What it does | Fails the build when |
| --- | --- | --- | --- |
| 1 | Checkout & metadata | checkout, compute `BUILD_VERSION`, `GIT_SHA`, `IMAGE_TAG_RESOLVED` | checkout fails |
| 2 | Build & unit test | `mvn verify` — unit + API tests, JaCoCo | any test fails |
| 3 | Package | produce the runnable JAR, `build-info.properties` | packaging fails |
| 4 | Docker build & tag | `docker build -t crfd:v<version>-<build>` | build fails |
| 5 | Selenium E2E quality gate | 5 journeys in a real Chrome against the built image | any journey fails → **no deploy** |
| 6 | Publish test reports | JUnit + JaCoCo + artifacts | - |
| 7 | Publish image | push to registry (only when `PUBLISH_IMAGE`) | push fails |
| 8 | Deploy fresh container | `docker rm -f crfd-app` then `docker run` the new tag | container does not start |
| 9 | Health check & smoke test | readiness gate + 11 smoke checks through the API | health not 200, or smoke fails |
| 10 | Provision node (Ansible) | playbook against a target (only when `RUN_ANSIBLE`) | playbook fails |

Post actions archive `pipeline-result.txt` and `build-info.properties`; on
failure the build prints that **deployment was not performed** and removes the
Selenium containers.

Triggers: `pollSCM('H/5 * * * *')` — a commit on `develop` is picked up within
about five minutes. Options: 45-minute timeout, keep 20 builds / 10 artifacts,
wipe workspace.

### 5.4 Parameters (all defined in the Jenkinsfile)

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ENVIRONMENT` | `dev` | dev / staging / production; drives container config and the env badge |
| `APP_PORT` | `8080` | host port the deployed container uses |
| `IMAGE_TAG` | empty | empty ⇒ `v<version>-<build number>` |
| `REGISTRY` | `docker.io` | registry host |
| `REGISTRY_CREDENTIALS_ID` | `dockerhub-credentials` | Jenkins credential id |
| `RUN_UNIT_TESTS` | true | run the Maven suite |
| `RUN_SELENIUM` | true | run the E2E gate |
| `PUBLISH_IMAGE` | false | push the image |
| `DEPLOY` | true | deploy after the gates |
| `RUN_ANSIBLE` | false | run the provisioning playbook |
| `CRFD_LOW_CONFIDENCE` | `0.30` | environment setting: low-confidence threshold |
| `CRFD_CRITICAL_LATENCY_MS` | `1500` | environment setting: critical latency SLA |

The two `CRFD_*` parameters are the required "parameterised environment
setting": the same image behaves differently per environment without a rebuild.

**Known quirk (say it before it bites):** JCasC re-applies the job on every
controller boot and drops the parameter definitions until the Jenkinsfile is
read once. So the **first build after a restart must be a plain "Build now"**;
after that, "Build with parameters" works.

### 5.5 Quality gates

- 37 tests per run: 12 unit, 20 API, 5 Selenium journeys.
- JaCoCo: 71% line, 45% branch.
- Selenium journeys: J1 dashboard renders indicators/charts/table, J2 event entry
  becomes searchable, J3 free-text search + status facet, J4 drill-down shows
  state/audit/alerts, J5 exception view + acknowledge.
- The gate is real: **build #7 is red** because a journey failed; the deploy stage
  was skipped. That is the evidence that the gate is not decorative.
- A defect found this way (`CRFD-117`, wrong empty-state in the exception view)
  was fixed and the suite re-run — `docs/evidence/selenium-defect-crfd-117/`.

### 5.6 Current state (quote these)

| Fact | Value |
| --- | --- |
| Last build | **#13, SUCCESS**, 37 min 12 s |
| Why so long | the Docker stage spent 2 104 s fetching Alpine packages from a slow mirror; the layer is now cached and later builds reuse it |
| Tests | 37, 0 failures |
| Coverage | 71% line / 45% branch |
| Image | `crfd:v3.3.5-13` |
| Parameters used | `ENVIRONMENT=dev`, `APP_PORT=8080`, `RUN_SELENIUM=true`, `PUBLISH_IMAGE=false`, `DEPLOY=true`, `RUN_ANSIBLE=false` |
| Artifacts | JAR, `build-info.properties`, `health.json`, `smoke-*.json`, `image-inspect.json`, `deployed-container.log`, `app-under-test.log`, `artifact-checksums.txt`, `pipeline-result.txt` |
| Deployed container | `crfd-app`, healthy, serving on 8080 |
| Security | login off for the demo; restore steps in `infra/jenkins/jenkins.yaml` |

---

## 6. Jenkins demo script (click by click)

Open <http://localhost:8085/job/crfd-dashboard-pipeline/>.

1. **Job page.** "This job is not configured by hand — it is seeded from
   `infra/jenkins/jenkins.yaml` and its definition is the `Jenkinsfile` in the
   repo, so the pipeline is versioned like the code."
2. **Build History → #13 → Stage View.** Ten columns, all green except the two
   skipped gates. Point at the durations: tests 33 s, Docker 2 104 s, Selenium
   63 s, deploy 2.4 s, health 17 s.
3. **Console → tail.** `Deployed crfd:v3.3.5-13 to dev on port 8080`, then the
   post-action line. Scroll up to show `docker build` and the Selenium run.
4. **Test Result.** 37 tests, 0 failures. Open `DashboardJourneyIT` to show the
   five journeys by name.
5. **Coverage Report.** 71% line — "the number that would fail a coverage gate if
   we added one".
6. **Artifacts.** The JAR (the deployable unit), plus the evidence files: health
   JSON, smoke JSON, image inspect, container log.
7. **Parameters.** Show `ENVIRONMENT`, `APP_PORT`, `RUN_SELENIUM`,
   `PUBLISH_IMAGE` — "the same pipeline deploys to dev, staging or production
   without editing anything."
8. **Build #7 (red).** "This is the gate working: Selenium failed, so stage 8
   never ran and the old container kept serving."
9. **Changes.** Commits that triggered the build, with the commit hashes that
   match `git log`.
10. Optional: **Build with parameters** → `ENVIRONMENT=staging`,
    `APP_PORT=18080`, `PUBLISH_IMAGE=false`, `RUN_ANSIBLE=false` → Build. It
    recreates `crfd-app` on 18080 from a fresh image.

**Do not start a build during the demo unless you have ~4 minutes.** The Docker
layer cache is warm now, so it should be far quicker than #13 — but "should" is
not "will". If you do run it, say beforehand: the Selenium suite deliberately
posts one invalid event to prove quarantine, and that single rejection can nudge
the 24-hour success rate under 95%, flipping the dashboard to `DEGRADED` and
`/health/slo` to 503. That is the readiness-vs-SLO behaviour, not an outage —
readiness stays 200.

---

## 7. How Docker is set up

### 7.1 Containers (Docker Desktop → Containers, filter `crfd`)

| Container | Image | Ports | Role | Network |
| --- | --- | --- | --- | --- |
| `crfd-app` | `crfd:v3.3.5-13` | 8080 → 8080 | the product, deployed by Jenkins | `crfd-ci-net` (alias `crfd-live`) |
| `crfd-jenkins` | `jenkins/jenkins:lts-jdk21` | 8085 → 8080, 50000 | CI/CD controller | `crfd-ci-net` |
| `crfd-selenium-standalone` | `selenium/standalone-chrome:4.25.0` | 4444, 7900 | browser for the E2E gate | `crfd-ci-net` |
| `crfd-registry` | `registry:2` | 5001 → 5000 | local image registry | `crfd-ci-net` |
| `crfd-node` | `crfd-node:latest` | - | the Ansible target node (stopped; started for the provisioning demo) | `crfd-demo-net` |

The `crfd-app` container is the one the pipeline created. It is **stateless**:
delete it and the data is still there.

### 7.2 Volumes

| Volume | Mounted at | Holds |
| --- | --- | --- |
| `crfd-data` | `/app/crfd-data` in `crfd-app` | **`crfd.mv.db` — the entire database** |
| `jenkins_jenkins-home` | `/var/jenkins_home` | Jenkins jobs, builds, plugins, config |
| `jenkins_registry-data` | `/var/lib/registry` | pushed images |

`crfd.mv.db` is currently ~1.1 MB. It survives `docker rm`, image rebuilds and
container restarts — that is how the deployed container stays replaceable while
keeping its data.

### 7.3 Networks and images

- `crfd-ci-net` — Jenkins, Selenium, the app and the registry. Container-name DNS
  is what lets the pipeline say `crfd-app:8080` instead of `localhost`.
- `crfd-demo-net`, `crfd-selenium-net` — the Ansible target and the local
  Selenium overlay.
- Images: `crfd:v3.3.5-7 … v3.3.5-13` (one per build — that is the rollback
  stock), plus `crfd:latest`, `crfd:1.0.0` (compose build), `crfd-node`,
  `eclipse-temurin:21-jre-alpine`, `maven:3.9.9-eclipse-temurin-21`,
  `jenkins/jenkins:lts-jdk21`, `selenium/standalone-chrome:4.25.0`, `registry:2`.

### 7.4 The image itself (`Dockerfile`)

Two stages:

1. **build** — `maven:3.9.9-eclipse-temurin-21`; copies `pom.xml`, `.mvn/`,
   `mvnw` first so the dependency layer is cached; then copies `src`, builds the
   fat JAR.
2. **runtime** — `eclipse-temurin:21-jre-alpine`, ~401 MB; adds only `curl` and
   `tzdata`, creates a **non-root** user `crfd`, exposes 8080, declares
   `VOLUME /app/crfd-data`, and ships a `HEALTHCHECK` that calls
   `/api/v1/health`.

No build tools, no Maven, no source in the runtime image. That is why the
container is small and why the health check is meaningful to Docker itself.

### 7.5 Where the database actually is

There is **no database container**. The MVP uses embedded H2 2.2.224 in file
mode:

```
volume crfd-data  →  /app/crfd-data/crfd.mv.db  →  tables
```

This is a deliberate MVP choice: zero operational surface, data still survives
container replacement. The scale-out path is already written:
`docker-compose.mysql.yml` + the `mysql` Maven profile + `DB_URL`, which is what
you would enable when more than one app instance needs the same data.

Tables: `RECOMMENDATION_EVENT`, `EVENT_AUDIT`, `ALERT`, `RECOMMENDATION_FEEDBACK`.

---

## 8. Docker demo script (click by click)

1. **Docker Desktop → Containers**, filter `crfd`. Four running containers; say
   what each one is for (table above).
2. **`crfd-app` → Logs.** Show the Spring Boot startup line and
   `Started CrfdApplication`. Scroll to show request logs from the dashboard.
3. **`crfd-app` → Inspect.** Point at `Mounts`: `crfd-data → /app/crfd-data`.
   "The container can be destroyed; the data cannot."
4. **`crfd-app` → Files → `/app/crfd-data/crfd.mv.db`.** "This file *is* the
   database."
5. **`crfd-app` → Exec.** Optional: `ls -lh /app/crfd-data`, `ps`.
6. **Volumes tab.** `crfd-data`, `jenkins_jenkins-home`, `jenkins_registry-data`.
7. **Images tab.** Every `crfd:v3.3.5-N` is one pipeline build — "this is my
   rollback list: I can start build 12 again in one command."
8. **Networks tab.** `crfd-ci-net`: "Jenkins, Selenium and the app find each
   other by name."
9. **H2 console** (<http://localhost:8080/h2-console/>):
   `jdbc:h2:file:/app/crfd-data/crfd`, user `sa`, empty password. Run:

   ```sql
   SELECT status, COUNT(*) AS n
   FROM recommendation_event
   GROUP BY status ORDER BY status;

   SELECT code, severity, status, COUNT(*) AS n
   FROM alert
   GROUP BY code, severity, status ORDER BY code;
   ```

   That is the database answering live, from inside the running container.

Rollback line to say: **`docker run` the previous tag against the same volume** —
the schema and data are unchanged, so recovery is a container swap, not a restore.

---

## 9. Live numbers (refresh before the viva)

The dashboard's window is **24 hours**. As of this briefing:

| Metric | Value |
| --- | --- |
| Events in window | 245 |
| Processed | 233 (95.1% success) |
| Failed | 12 (4.9%) |
| Open exceptions | 190, of which 49 critical |
| Feedback records | 25 |
| Total events in DB | 630 |
| Average pipeline latency | 234 ms |

These move the moment **Generate traffic** is pressed — which is the point. The
seeded history from 29 Oct falls outside the 24-hour window, so an untouched
database shows zeros in the KPI cards; press **Generate traffic** once at the
start if the window is empty.

Health at this moment: `UP`, `sloBreached:false`, success rate 0.951 against the
0.95 objective. It is deliberately close: one extra failure tips it to
`DEGRADED`, which is a useful thing to demonstrate rather than hide.

---

## 10. The end-to-end story (if asked "walk me through a change")

1. Branch from `develop`, e.g. `feature/CRFD-105-alert-view`.
2. Commit with a conventional message; push; open a PR.
3. Jenkins polls `develop` and starts a build.
4. Stage 2 runs 32 unit/API tests; stage 3 packages the JAR.
5. Stage 4 builds `crfd:v<version>-<n>` from the Dockerfile.
6. Stage 5 runs the five Selenium journeys against that image in a real browser.
   If any journey fails, stages 8–9 are skipped and the running container is
   untouched.
7. Stage 8 removes the old `crfd-app` and starts the new tag on the same
   `crfd-data` volume.
8. Stage 9 waits for `/api/v1/health` to be 200, then runs 11 smoke checks and
   archives the responses.
9. Result: the dashboard on 8080 is the artefact of that build, carrying its
   version and commit in `build-info.properties`.

---

## 11. Likely questions, short answers

**Why H2 and not MySQL?**
MVP, one node: embedded file mode means no database server to run, patch or back
up, and the data still lives on a volume so containers stay disposable. The
MySQL path is written and wired (`docker-compose.mysql.yml`, `mysql` profile,
`DB_URL`) for when more than one app instance must share the data.

**Why not Kubernetes?**
Scope. The 15-week MVP targets a single node; Compose plus the Jenkins deploy
stage already gives repeatable, versioned deployments. Kubernetes would add
orchestration, ingress and secrets management without changing the demo's
outcome. It is listed under future work.

**How do you know a deployment succeeded?**
Stage 9 blocks on `/api/v1/health` returning 200 and then runs 11 smoke checks
through the API; the responses are archived as build artifacts. Docker also
health-checks the container itself every 30 s.

**How do you roll back?**
Every build keeps its own immutable tag (`crfd:v3.3.5-12`, `-11`, …). Rolling
back is starting the previous tag against the same `crfd-data` volume. The
Ansible playbook has a rollback step (`docs/evidence/ansible-05-rollback.log`).

**What stops a broken build reaching users?**
The Selenium gate runs *before* deploy and its failure skips the deploy stage.
Build #7 is the proof: red, deploy skipped, old container still serving.

**What is idempotent in this project?**
The Ansible playbook (second run reports no changes — `ansible-02-idempotency-second-run.log`),
the container deploy (`rm -f` then run, same result every time), JCasC (same
controller every boot) and event ingestion (business keys make a replayed event
an update, not a duplicate).

**What is the difference between health and the SLO?**
`/health` is readiness: 200 for UP and DEGRADED, 503 only for DOWN — a restart
fixes DOWN, a restart does not fix a low success rate. `/health/slo` is the
business objective (95% success): 503 when breached, for alerting, not for
restarting.

**How are secrets handled?**
Jenkins credentials store (`github-crfd`, `dockerhub-credentials`), injected at
build time; the repo contains placeholders only. The demo controller has login
disabled on purpose and is bound to localhost — the restore procedure is in
`infra/jenkins/jenkins.yaml`.

**Why is Jenkins itself in Docker?**
It makes the CI server reproducible from source: compose file, plugin list and
JCasC. Rebuilding the controller is one command, not a day of clicking. The cost
is the socket mount — Jenkins asks the host daemon to run containers, so those
containers are siblings and must be addressed by name, not `localhost`.

**What was the hardest part?**
Two things worth naming: Docker-outside-of-Docker networking (the `localhost`
trap, solved with `crfd-ci-net` aliases) and Jenkins Configuration-as-Code
strictness (a withdrawn plugin aborts the whole plugin list; one unknown
attribute aborts boot). Both are in the troubleshooting guide with the fix.

**What is not covered?**
No authentication/authorisation on the dashboard (single-operator MVP), no
multi-tenancy, no real recommendation engine (events are ingested, not produced),
no HA, no TLS, coverage gate not enforced, and the Selenium suite covers the five
critical journeys rather than every path. See
`docs/16-limitations-and-future-enhancements.md`.

**How would you scale this?**
MySQL for shared state; several `crfd-app` replicas behind Nginx; push images to
a real registry; then Kubernetes for scheduling and rolling updates; keep the
same Jenkinsfile gates.

---

## 12. If something breaks during the viva

| Symptom | Cause | Fix |
| --- | --- | --- |
| Dashboard KPI cards show 0 | nothing in the last 24 h | press **Generate traffic** once |
| Dashboard says `DEGRADED`, `/health/slo` 503 | success rate under 95% (often the deliberate rejection from Selenium J3) | explain readiness vs SLO; keep demoing, alerts view is the point |
| Jenkins shows a login page | `config.xml` was recreated with security on | `docker exec crfd-jenkins sed -i 's|<useSecurity>true</useSecurity>|<useSecurity>false</useSecurity>|' /var/jenkins_home/config.xml` then `docker restart crfd-jenkins` |
| "Build with parameters" returns HTTP 400 | parameters not yet registered after a restart | run one plain **Build now** first |
| Port 8080 already in use | the compose stack or another app is running | use `APP_PORT=18080`, or stop the other stack |
| `crfd-app` missing | it was removed and not recreated | re-run the deploy stage, or `docker run` the newest `crfd:v3.3.5-*` tag against volume `crfd-data` |
| Docker Desktop not running | - | start it, wait for the whale to settle; containers have `restart: unless-stopped` and come back by themselves |

Useful commands:

```bash
docker ps --filter name=crfd
docker logs -f crfd-app
docker inspect crfd-app --format '{{json .Mounts}}'
curl -s http://localhost:8080/api/v1/health
curl -s -o NUL -w "%{http_code}" http://localhost:8080/api/v1/health/slo
docker exec crfd-app ls -lh /app/crfd-data
```

---

## 13. Map of "where is that requirement evidenced"

| Requirement | Evidence |
| --- | --- |
| Git workflow, branches, PRs, tags | `docs/06-git-and-github-workflow.md`, `git log --graph`, tags `v0.1.0-mvp`, `v1.0.0` |
| Merge conflict created and resolved | `docs/evidence/merge-conflict/` |
| Jenkins builds on commit, archives artifact | job config, build #13 artifacts, `pollSCM` |
| Parameterised environment setting | `CRFD_LOW_CONFIDENCE`, `CRFD_CRITICAL_LATENCY_MS`, `ENVIRONMENT` |
| Selenium 3–5 journeys, screenshots on failure | `docs/10`, `docs/evidence/selenium-defect-crfd-117/` |
| Failed test stops deploy | build #7, stage 8 skipped |
| Docker lifecycle, tags, logs, stop/restart/remove | `docs/12-docker-lifecycle.md`, `docs/evidence/docker-*.txt` |
| Jenkins builds versioned image, deploys fresh container | `Jenkinsfile` stages 4 and 8, `docs/13-jenkins-docker-cd.md` |
| Ansible clean node, idempotency, health, rollback | `docs/14`, `docs/evidence/ansible-01..05` |
| End-to-end workflow, report, troubleshooting, limitations | `docs/15`, `docs/17`, `docs/16` |
| Presentation / viva | this file, `docs/19-*.docx` |
