# First green Jenkins pipeline run

Recorded on 28 October 2026 from the controller at <http://localhost:8085>
(`admin` / `crfd-admin`), job `crfd-dashboard-pipeline`, build **#8**.

## What the run proves

The pipeline builds, tests, containerises, gate-checks, deploys and smoke-tests
the application from a clean checkout, and it does so on a controller that is
itself a container talking to the host Docker engine over the socket. Nothing
was executed by hand on the host: the application container on port 18080 is the
one the pipeline created.

```
Started by user CRFD Administrator
Checking out git file:///workspace/crfd ... (origin/develop)
Checking out Revision 81dfe88... (origin/develop)
```

## Parameters

| Parameter | Value |
| --- | --- |
| `ENVIRONMENT` | `staging` |
| `APP_PORT` | `18080` |
| `RUN_SELENIUM` | `true` |
| `PUBLISH_IMAGE` | `false` (no registry push in this run) |
| `DEPLOY` | `true` |
| `RUN_ANSIBLE` | `false` (demonstrated separately, see `ansible-01..05-*.log`) |

## Stages

| # | Stage | Result |
| --- | --- | --- |
| 1 | Checkout & metadata | PASS - commit `81dfe88`, branch `develop`, version `3.3.5` |
| 2 | Build & unit test | PASS - `Tests run: 32, Failures: 0, Errors: 0` (6 + 6 unit, 20 API) |
| 3 | Package | PASS - `target/content-recommendation-feedback-dashboard.jar` (57 MB) |
| 4 | Docker build & tag | PASS - `crfd:v3.3.5-8`, `crfd:81dfe88`, `crfd:latest` |
| 5 | Selenium E2E quality gate | PASS - 5 journeys, `Tests run: 5, Failures: 0` |
| 6 | Publish test reports | PASS - JUnit 37/37, JaCoCo coverage |
| 7 | Publish image | SKIPPED - `when { params.PUBLISH_IMAGE }` |
| 8 | Deploy fresh container | PASS - removed and recreated `crfd-app` from `crfd:v3.3.5-8` |
| 9 | Health check & smoke test | PASS - readiness gate, then 11 smoke checks |
| 10 | Provision node (Ansible) | SKIPPED - `when { params.RUN_ANSIBLE }` |

Build result: **SUCCESS** (`result=SUCCESS`, `image=crfd:v3.3.5-8`).

## Evidence from the controller

Archived in the build (`builds/8/archive/target/`):

- `content-recommendation-feedback-dashboard.jar` - the deployable artifact
- `health.json`, `smoke-ingest.json`, `smoke-query.json`, `smoke-summary.json`
- `image-inspect.json` - labels of the image that was built
- `deployed-container.log`, `app-under-test.log` - container stdout
- `pipeline-result.txt` - `result=SUCCESS image=crfd:v3.3.5-8 commit=81dfe88`
- `build-info.properties` - version, tag, build URL, commit, environment

Smoke test excerpt - an event ingested through the pipeline-deployed container
and read back through the query API:

```
+ curl -fsS -X POST http://crfd-live:8080/api/v1/events ...
  {"eventId":"EVT-0AFBEFB1", ... "status":"RECEIVED"}
+ curl -fsS http://crfd-live:8080/api/v1/events?q=ITM-SMOKE
  {"eventId":"EVT-0AFBEFB1", ... "status":"PROCESSED","latencyMs":205}
```

## Docker state after the run

```
crfd-app                | crfd:v3.3.5-8                   | Up (healthy) | 0.0.0.0:18080->8080/tcp
crfd-jenkins            | jenkins/jenkins:lts-jdk21       | Up           | 0.0.0.0:8085->8080/tcp
crfd-selenium-standalone| selenium/standalone-chrome:4.25.0| Up          | 0.0.0.0:4444->4444/tcp
crfd-registry           | registry:2                      | Up           | 0.0.0.0:5001->5000/tcp

crfd:81dfe88   401MB   (2 minutes ago, built by Jenkins)
crfd:latest    401MB
crfd:v3.3.5-8  401MB
```

The application is served at <http://localhost:18080/> and reports
`"environment":"staging"`, which is the parameter the build was started with.

## Readiness versus SLO, as designed

`GET /api/v1/health` at the end of the run:

```
{"status":"DEGRADED", "environment":"staging", "database":"UP",
 "checks":{"pipelineSuccessRate":"0.8333","sloMinimum":"0.95"}}
```

The readiness check is `200` because the service is up and able to take traffic;
the business objective is breached because journey J3 submits an impression
without a confidence score on purpose, and that rejected event lowers the 24-hour
success rate below 0.95. `/api/v1/health/slo` returns `503` in this state, which
is the documented split between readiness and business SLO (CRFD-105).

## Defects found and fixed by actually running the pipeline

The pipeline had never been executed by a controller. Running it exposed five
real defects, all fixed on `develop` in this order:

| Commit | Defect |
| --- | --- |
| `7353169` | `Jenkinsfile` had shell-style `#` comment lines outside a `sh` block, so it failed to compile as Groovy before stage 1. `plugins.txt` listed two withdrawn plugins, which aborted the whole plugin install; `jenkins.yaml` used five JCasC attributes that are now fatal (or removed) and a Job DSL `triggers{}` block the plugin rejects. Local checkouts were blocked without `ALLOW_LOCAL_CHECKOUT`. |
| `d408ae9` | Build metadata was computed in the declarative `environment{}` block, i.e. before `checkout scm`, so `BUILD_VERSION` came out null and the image was tagged `crfd:null`. |
| `917346d` | Windows checkouts store `mvnw` as `100644`, so the Docker build died with `./mvnw: Permission denied` (exit 126). |
| `81dfe88` | J4 asserted that a low-confidence alert sorted first. The confidence and latency rules are independent, so one event can raise both; the assertion now checks presence. J5 selects its own `LOW_CONFIDENCE` row instead of `items.get(0)`. |

Failed builds #1-#7 are the iterations that found these; #5 and #6 were
transient (a truncated `zstd-jni` download after the machine was suspended).

## Later runs (29 October 2026)

Three further runs confirm the pipeline is repeatable, not a one-off:

| Build | Parameters | Result |
| --- | --- | --- |
| #9 | defaults (`ENVIRONMENT=dev`, `APP_PORT=8080`) | SUCCESS |
| #10 | defaults (re-registration run) | SUCCESS - 32 unit/API + 5 Selenium |
| #11 | `ENVIRONMENT=staging`, `APP_PORT=18080` | SUCCESS - `crfd:v3.3.5-11` deployed on 18080 |

Build #11 archived:

```
build.version=3.3.5
build.tag=v3.3.5-11
build.number=11
git.commit=90c6c82
environment=staging
result=SUCCESS
image=crfd:v3.3.5-11
```

The container the pipeline created, and the product working through it:

```
crfd-app | crfd:v3.3.5-11 | Up (healthy) | 0.0.0.0:18080->8080/tcp

GET  /                                  -> 200 (15,137 bytes, dashboard shell)
POST /api/v1/events                     -> EVT-997759E9, status RECEIVED
GET  /api/v1/events?q=ITM-DEMO-29       -> totalElements=1, status PROCESSED, latencyMs=117
GET  /api/v1/metrics/summary            -> totalEvents=10, successRate=0.9, ctr=0.25, avgLatencyMs=164.9
```

Health at the end of #11 is `DEGRADED` with `pipelineSuccessRate: 0.8889` against
`0.95`, again because the Selenium journeys intentionally submit rejected
events. Readiness stays `200`; `/api/v1/health/slo` reports the breach.

One operational note: JCasC re-applies the job definition on every controller
boot, which clears the `ParametersDefinitionProperty` that a Jenkinsfile run had
registered. The first build after a restart is therefore a plain `build` (it
re-registers the parameters from the `Jenkinsfile`); `buildWithParameters` works
again from the next build onwards.

## Reproduce

```bash
docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d
# then, with the tools installed in the controller (see docs/18-local-runbook.md):
curl -u admin:crfd-admin -X POST \
  "http://localhost:8085/job/crfd-dashboard-pipeline/buildWithParameters?ENVIRONMENT=staging&APP_PORT=18080"
```

Note that the controller image ships neither `mvn` nor the `docker` CLI; they are
installed into the running container before the first build, as recorded in
`docs/18-local-runbook.md`.
