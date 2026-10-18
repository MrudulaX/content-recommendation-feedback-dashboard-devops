# 18 — Local runbook

Everything needed to stand this project up from nothing, in order. Each step says what
it proves, so the runbook doubles as a checklist of what has been demonstrated.

Every command below was run on the machine this project was built on: **Windows 11,
PowerShell, Docker Desktop (Linux containers)**, with the Linux equivalents given where
they differ. `.\mvnw.cmd` is PowerShell; `./mvnw` is bash.

If something fails, `docs/17-troubleshooting-guide.md` has the specific failure with the
message it produced.

---

## 0. Ports, so nothing collides

| Port | Used by | Notes |
| --- | --- | --- |
| **8080** | the application (default) | `SERVER_PORT` to change |
| **18080** | the application **container**, published | used throughout the docs |
| **80** | Nginx, in the Compose stack | |
| **4444 / 7900** | Selenium standalone Chrome | VNC on 7900 |
| **8085** | Jenkins | |
| **5001** | local Docker registry | |
| 3306 | MySQL, when the profile is on | |

**Check before starting:** 8080 is frequently taken by something unrelated. If it is,
use 18081 and adjust:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object LocalPort, OwningProcess
```

---

## 1. Prerequisites

| Tool | Version used | Check |
| --- | --- | --- |
| JDK | **Temurin 21** | `java -version` |
| Maven | via the wrapper, **3.9.16** | `.\mvnw.cmd -v` |
| Docker | 29.7.2, Linux containers | `docker version --format '{{.Server.Os}}'` |
| Node | only for the optional UI checks | `node --version` |
| Git | 2.55 | `git --version` |
| Ansible | **not needed locally** - it runs in a pinned container | `docker` |
| Jenkins | **not needed locally** - it runs in a container | `docker` |

Two of these matter more than they look:

- **`java -version` must say 21.** The project compiles to class file 65. Maven and the
  runtime each have their own view of `JAVA_HOME`, so a build that succeeds and an
  application that refuses to start usually points here (`docs/17` §1.1).
- **Docker must be in Linux-container mode.** The image is Alpine; Windows containers
  cannot run it.

Verify the whole toolchain in four commands:

```powershell
java -version
.\mvnw.cmd -v
docker version --format "client={{.Client.Version}} server={{.Server.Version}} os={{.Server.Os}}"
git --version
```

---

## 2. Build and unit-test

```powershell
cd content-recommendation-feedback-dashboard
.\mvnw.cmd -B clean package
```

Expected:

```text
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0  -- AlertServiceTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0  -- EventServiceTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0  -- EventApiTest
[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**What this proves.** 31 tests covering the domain services and the whole API contract
over a real Spring context and a real (in-memory) database. The Selenium journeys are
*not* in this run - they need a browser, and they are step 7.

Offline, once dependencies are cached: `.\mvnw.cmd -B -o clean package`.

To see a coverage report:

```powershell
.\mvnw.cmd -B test jacoco:report
start target\site\jacoco\index.html
```

---

## 3. Run the application locally

```powershell
java -jar target\content-recommendation-feedback-dashboard-1.0.0-SNAPSHOT.jar
```

Expected, within about 10 seconds:

```text
Tomcat started on port 8080 (http)
Started DashboardApplication in 6.1 seconds
```

### Useful URLs

| URL | What |
| --- | --- |
| `http://localhost:8080/` | the dashboard |
| `http://localhost:8080/api/v1/health` | readiness |
| `http://localhost:8080/api/v1/health/slo` | the business SLO gate |
| `http://localhost:8080/swagger-ui/index.html` | the API catalogue |
| `http://localhost:8080/h2-console` | the database console (JDBC URL `jdbc:h2:file:./crfd-data/crfd`, user `sa`, empty password) |

### Configuration without a rebuild

Every setting is externalised, which is what makes the pipeline's parameterised deploy
possible:

```powershell
$env:SERVER_PORT            = "18081"
$env:CRFD_ENVIRONMENT       = "local"
$env:CRFD_LOW_CONFIDENCE    = "0.25"
$env:CRFD_CRITICAL_LATENCY_MS = "800"
$env:CRFD_ASYNC_ENABLED     = "false"     # deterministic: process on the request thread
$env:CRFD_SIMULATION_ENABLED = "true"     # the demo traffic generator
java -jar target\*.jar
```

`CRFD_ASYNC_ENABLED=false` is the one worth knowing: it makes ingestion synchronous, so
a test or a demo sees the pipeline finish before the response returns. The automated
tests use it for exactly that reason.

---

## 4. Exercise the API

```bash
BASE=http://localhost:8080
```

**Ingest an event.** Omit `eventId` and the server mints an `EVT-` business key:

```bash
curl -s -X POST $BASE/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": "U-1001", "itemId": "ITM-4471", "itemTitle": "Noise cancelling headphones",
        "source": "COLLABORATIVE_FILTER", "surface": "HOME_FEED",
        "eventType": "CLICK", "score": 0.87, "latencyMs": 210
      }'
```

Expect `201` and an `eventId` of the form `EVT-4F9C1A2B7D3E`. **That key shape is a
contract**: the dashboard, the smoke test and the Ansible assertions all depend on it,
and `docs/11` is the story of what happened when the write path disagreed.

**Find it:**

```bash
curl -s "$BASE/api/v1/events?q=ITM-4471" | jq '.content[0]'
curl -s "$BASE/api/v1/events?status=PROCESSED&source=COLLABORATIVE_FILTER&size=5" | jq '.totalElements'
```

**Drill in, with the audit timeline:**

```bash
curl -s $BASE/api/v1/events/EVT-4F9C1A2B7D3E | jq '.status, .auditTrail'
```

**Move its status** (the audited manual transition):

```bash
curl -s -X PATCH $BASE/api/v1/events/EVT-4F9C1A2B7D3E/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"PROCESSED","reason":"manually confirmed","changedBy":"runbook"}'
```

**Summary and time series:**

```bash
curl -s "$BASE/api/v1/metrics/summary?windowHours=24"   | jq '{total, successRate, p95LatencyMs}'
curl -s "$BASE/api/v1/metrics/timeseries?granularity=hour&windowHours=6" | jq '.points | length'
```

**The exception queue:**

```bash
curl -s "$BASE/api/v1/alerts?status=OPEN" | jq '.content[] | {alertRef, code, severity}'
curl -s -X POST $BASE/api/v1/alerts/ALT-1A2B3C4D/acknowledge \
  -H 'Content-Type: application/json' -d '{"decidedBy":"runbook"}'
```

**Explicit feedback:**

```bash
curl -s -X POST $BASE/api/v1/feedback \
  -H 'Content-Type: application/json' \
  -d '{"itemId":"ITM-4471","rating":2,"reason":"too expensive","submittedBy":"U-1001"}'
```

**The two behaviours that are contracts, not bugs** - worth trying once each:

```bash
# idempotent ingestion: the second post is rejected and an alert is raised
curl -s -X POST $BASE/api/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"EVT-FIXED0001","userId":"U-1","itemId":"ITM-1","source":"TRENDING","surface":"HOME_FEED","eventType":"CLICK","score":0.5}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"EVT-FIXED0001","userId":"U-1","itemId":"ITM-1","source":"TRENDING","surface":"HOME_FEED","eventType":"CLICK","score":0.5}'
# → 409

# a payload with no itemId is QUARANTINED and raises SCHEMA_MISMATCH, not a 500
curl -s -X POST $BASE/api/v1/events -H 'Content-Type: application/json' \
  -d '{"userId":"U-1","source":"TRENDING","surface":"HOME_FEED","eventType":"CLICK","score":0.5}'
```

---

## 5. Generate some data, then look at the dashboard

```bash
curl -s -X POST "$BASE/api/v1/dev/simulate?count=120" | jq .
```

Then open **`http://localhost:8080/`**. You should see the KPI strip, the throughput
chart, the searchable table, and alerts on the queue.

The simulator is disabled by the production profile and returns 403 there - an endpoint
that writes fake data is a security finding if it ships enabled, so it fails closed.

---

## 6. The container

```powershell
docker build -t crfd:1.0.0 .
docker images crfd
```

Expected: ~**401 MB**. The final stage is a JRE-only Alpine image running as a non-root
user; nothing at runtime can compile Java.

```powershell
# note the named volume - see docs/17 §4.1 for what happens without it
docker run -d --name crfd-app --restart unless-stopped `
    -p 18080:8080 `
    -v crfd-data:/app/crfd-data `
    -e CRFD_ENVIRONMENT=demo `
    -e CRFD_CRITICAL_LATENCY_MS=900 `
    crfd:1.0.0

docker inspect --format "{{.State.Health.Status}}" crfd-app     # → healthy
```

**Which revision is this?** `docker ps` cannot tell you; the image can:

```powershell
docker inspect crfd:1.0.0 --format '{{json .Config.Env}}'
# ["APP_VERSION=1.0.0-SNAPSHOT","BUILD_REVISION=local",...]
```

**Smoke test - 11 checks:**

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
```

Expected: `11/11 PASS`, saved to `docs/evidence/smoke-test.txt`.

**The lifecycle, as a reviewed sequence** (build, tag, run, logs, health, exec, stop,
start, restart, remove, clean):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\docker-lifecycle.ps1
```

The point of running it deliberately is to find out, once, that `restart` preserves the
volume mount and that `stop` takes a few seconds because shutdown is graceful - rather
than discovering both during an incident.

### Compose, with the Nginx edge

```powershell
docker compose up -d
docker compose ps
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/healthz     # → 200, from Nginx
```

Nginx waits on `depends_on: service_healthy`, so it cannot come up in front of an
application that is not ready. **If Nginx never starts, read `docs/17` §3.1 before
anything else** - it is the project's signature failure.

---

## 7. The Selenium journeys

The five critical journeys. Either against the local JAR (step 3) or against the
container (step 6).

### 7.1 Containerised browser (what was verified)

```powershell
docker run -d --name crfd-selenium -p 4444:4444 -p 7900:7900 `
    --network crfd-net selenium/standalone-chrome:4.25.0

$env:SELENIUM_REMOTE_URL = "http://localhost:4444/wd/hub"
.\mvnw.cmd -B -P selenium failsafe:integration-test failsafe:verify `
    "-Dselenium.baseUrl=http://crfd-app:8080" `
    "-Dselenium.headless=true" `
    "-Dselenium.browser=chrome"
```

Expected:

```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

Two things to notice, both of which caused confusion before they were understood:

- **`SELENIUM_REMOTE_URL` is reached from the host**, so `localhost` is right.
- **`selenium.baseUrl` is reached from the *browser container***, so it must be a
  Docker network alias - `localhost` inside that container is the container itself.

Live view of the browser while it runs: `http://localhost:7900` (VNC, password
`secret`).

### 7.2 Screenshots on failure

A failing journey writes a screenshot, the page source and the JUnit text. The one real
failure this project had is preserved:

```text
docs/evidence/selenium-defect-crfd-117/
  J2_-_...-20261018-174501.png     what the user saw
  J2_-_...-20261018-174501.html    what the DOM was
  J2_-_...-20261018-174501.txt     what the assertion wanted
```

That triple is the model for triaging any journey failure: the assertion tells you the
expectation, the DOM tells you the reality, the picture tells you which of the two is
wrong.

### 7.3 Local browser instead

```powershell
.\mvnw.cmd -B -P selenium failsafe:integration-test failsafe:verify `
    "-Dselenium.baseUrl=http://localhost:8080" "-Dselenium.headless=true"
```

Only if the local Chrome and its driver agree (`docs/17` §5.3). The container is more
reliable and is what the pipeline uses.

---

## 8. MySQL instead of H2

```powershell
docker compose -f docker-compose.mysql.yml up -d
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:DB_URL      = "jdbc:mysql://localhost:3306/crfd?useSSL=false&allowPublicKeyRetrieval=true"
$env:DB_USER     = "crfd"
$env:DB_PASSWORD = "crfd"
java -jar target\*.jar
```

**What this proves.** The schema and the queries are portable. It does not prove
concurrency: H2 file mode is single-writer, and MySQL is the shape a multi-instance
deployment needs - see limitation B1 in `docs/16`.

---

## 9. WAR on an external Tomcat (the brief's Tomcat target)

```powershell
.\mvnw.cmd -B -P war clean package
dir target\crfd.war

docker run -d --name crfd-tomcat -p 18090:8080 `
    -v "$PWD\target\crfd.war:/usr/local/tomcat/webapps/crfd.war" `
    tomcat:10.1-jdk21
```

Open `http://localhost:18090/crfd/`. Embedded Tomcat is the default deployment because a
container wants a fat JAR; the external-Tomcat path stays buildable because the brief
names it. `docs/09` covers which to use and why.

---

## 10. Jenkins, as code

```powershell
docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d
docker logs -f crfd-jenkins          # watch JCasC apply; ~60 s on first run
```

Expected: Jenkins on **`http://localhost:8085`**, admin credentials from
`infra/jenkins/jenkins.yaml`, the plugins at their pinned versions, a local registry on
**5001**, and a Job DSL **seed job** that creates the pipeline job.

```powershell
docker images | Select-String registry
docker network inspect crfd-ci-net --format '{{range .Containers}}{{.Name}} {{end}}'
```

**The full pipeline needs a real GitHub remote** to check out from. Until then, run the
stages by hand - which is what was done, and what `docs/13` §9 records as limitation A3.
The networking rule to remember when you do wire it up: Jenkins is a *sibling* container,
so build steps address `crfd-app`, `crfd-selenium` and `crfd-live` by alias, never
`localhost`.

---

## 11. Provisioning, end to end, with evidence

One command runs the whole demonstration:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ansible-demo.ps1 -KeepRunning
```

```bash
# Linux / Git Bash
scripts/ansible-demo.sh
```

It builds a disposable clean node (`crfd-node`: SSH and a Docker engine, nothing else)
and a pinned Ansible controller, then:

| Step | What it runs | Expected |
| --- | --- | --- |
| 1 | `site.yml -e app_version=0.1.0` | `ok=30 changed=9 failed=0` |
| 2 | the identical run again | **`ok=30 changed=0 failed=0`** - idempotency |
| 3 | `deploy.yml -e app_version=1.0.0` | `ok=20 changed=4 failed=0` |
| 4 | `healthcheck.yml` | `status=UP ... slo=WITHIN_OBJECTIVE` |
| 5 | `rollback.yml` | `RECOVERED crfd-node -> crfd:0.1.0, health=UP` |

Logs: `docs/evidence/ansible-01..05-*.log`, summarised in `ansible-summary.txt`.

**Step 2 is the deliverable.** `changed=0` is not a claim in a document, it is Ansible's
own count of the tasks that did anything, and the answer is none.

`-KeepRunning` leaves the node up so you can inspect it:

```powershell
docker exec crfd-node cat /etc/crfd/previous-release.txt
docker exec crfd-node cat /etc/crfd/release-history.log
docker exec crfd-node wget -qO- http://localhost:8080/api/v1/health
```

---

## 12. Regenerating the evidence

Every file in `docs/evidence/` came from a command in this runbook. To reproduce them
all:

```powershell
# 1. the regression tests failing against the pre-fix tree
#    (requires checking out the parent of the fix commits - see docs/07 §6.5)
# 2. container and runtime facts
docker images crfd | Out-File docs\evidence\docker-images.txt
docker inspect crfd-app | Out-File docs\evidence\docker-inspect.txt
# 3. health and SLO
curl.exe -s http://localhost:18080/api/v1/health     | Out-File docs\evidence\health-and-slo.txt
curl.exe -s http://localhost:18080/api/v1/health/slo | Out-File -Append docs\evidence\health-and-slo.txt
# 4. the smoke test
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
# 5. Selenium
#    (the command in §7.1, output redirected)
# 6. Ansible
powershell -ExecutionPolicy Bypass -File scripts\ansible-demo.ps1
```

---

## 13. Teardown

```powershell
# containers
docker rm -f crfd-app crfd-selenium crfd-node crfd-live crfd-jenkins 2>$null
docker rm -f crfd-tomcat crfd-mysql registry 2>$null

# networks and volumes
docker network rm crfd-net crfd-ci-net crfd-demo-net 2>$null
docker volume rm crfd-data 2>$null          # ← this deletes the event history

# images
docker rmi crfd:1.0.0 crfd:latest crfd:0.1.0 crfd-node:latest crfd-ansible:latest

# compose stacks
docker compose -f docker-compose.yml down
docker compose -f infra/jenkins/docker-compose.jenkins.yml down
docker compose -f docker-compose.mysql.yml down
```

`docker compose down -v` also removes the volumes - which is what you want on a machine
you are cleaning, and not what you want on one you are demonstrating from.

---

## 14. The 60-second version

```powershell
.\mvnw.cmd -B clean package                                   # 31 tests
docker build -t crfd:1.0.0 .
docker run -d --name crfd-app -p 18080:8080 -v crfd-data:/app/crfd-data crfd:1.0.0
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
start http://localhost:18080/
```

That is: build, test, containerise, run, verify, and look at it. Everything else in this
runbook - the gate, the pipeline, the provisioning - exists to make those six steps
repeatable by someone else, on a machine that is not this one, without a conversation.
