# 08 — Jenkins: installation, configuration and the CI job

## 1. Why the controller is installed from source control

The brief asks for Jenkins to be installed *and configured*. The temptation is to
`docker run` a Jenkins image, click through the setup wizard, install plugins by
hand, paste a job definition into the UI, and call it configured. That produces a
controller which:

- exists only on one machine,
- cannot be reviewed or diffed,
- cannot be rebuilt after a disk failure without repeating the clicking,
- and gives no evidence that the configuration is intentional.

So the controller is **provisioned as code**, exactly like the application nodes:

```
infra/jenkins/
├── docker-compose.jenkins.yml    the controller, the local registry, the browser node
├── jenkins.yaml                  Configuration as Code (JCasC): tools, credentials, jobs
└── plugins.txt                   the exact plugin set the Jenkinsfile needs
```

Bringing it up is one command:

```bash
docker compose -f infra/jenkins/docker-compose.jenkins.yml up -d
#  -> http://localhost:8085
```

Nothing is hand-clicked. A reviewer can read `jenkins.yaml` and know exactly what
the controller is.

## 2. What the compose stack contains

| Service | Purpose |
| --- | --- |
| `jenkins` | The controller: JDK 21, Maven 3.9, the Docker CLI **and the host's Docker socket** |
| `registry` | A local `registry:2` on port 5001, so a versioned image can be published without a Docker Hub account |
| `selenium` | `selenium/standalone-chrome:4.25.0`, available for local debugging |

Three details matter:

**Docker-outside-of-Docker.** The socket is mounted
(`/var/run/docker.sock:/var/run/docker.sock`), so the pipeline builds and runs
containers on the host daemon. This is why the pipeline must address sibling
containers by *network alias* rather than `localhost` — see §5.

**A pinned network.** The compose network is pinned to the name `crfd-ci-net`.
Containers the pipeline creates join that same network, which is what lets the
Selenium gate, the deploy stage and the health check reach each other by name.

**`JENKINS_ADMIN_ID` / `JENKINS_ADMIN_PASSWORD` are environment variables** fed to
JCasC, not literals in a committed config. The setup wizard is disabled
(`-Djenkins.install.runSetupWizard=false`) because JCasC owns the configuration;
leaving the wizard on would create a second, unaudited way to configure the
controller.

## 3. Configuration as Code

`jenkins.yaml` declares, rather than describes:

- **the localisation and the `admin` user**, from the environment;
- **three Jenkins tools** — `git`, `JDK21` and `M3` (Maven 3.9.9) — auto-installed
  and *named*, so the `Jenkinsfile` can rely on them without the controller being
  hand-configured;
- **a Docker cloud** pointed at the mounted socket, so the agent can build and run
  containers;
- **credentials entries** (`dockerhub-credentials`) referenced by the pipeline as
  an *id*. The secret is supplied at runtime; nothing secret is committed;
- **a seed job** (Job DSL) that registers the CRFD pipeline from SCM using
  `CRFD_REPO_URL` and `CRFD_REPO_BRANCH` (default `develop`), pointing at the
  `Jenkinsfile` in the repository. The seed job deliberately does **not** redeclare
  the build parameters: the `Jenkinsfile` owns them, and because the job is
  configured with `lightweight(false)` Jenkins checks the file out to read them, so
  the job is parameterised from the moment it is seeded. Two copies of the
  parameter list would drift, and the `Jenkinsfile`'s copy wins silently at build
  time.

`plugins.txt` pins the plugin set. The important ones and why they are needed:

| Plugin | Needed for |
| --- | --- |
| `workflow-aggregator` / `pipeline-*` | Declarative pipeline |
| `configuration-as-code` | Reading `jenkins.yaml` |
| `job-dsl` | The seed job |
| `git` | Checkout |
| `docker-workflow` | `docker.withRegistry` in the publish stage |
| `junit` | Publishing Surefire and Failsafe XML |
| `jacoco` | The coverage report and trend |
| `htmlpublisher` | The JaCoCo HTML report |
| `ansicolor` | Readable `ansiColor('xterm')` output |
| `ws-cleanup` | The end-of-build workspace cleanup |
| `timestamper` | Timestamps on every log line |
| `credentials-binding` | Registry credentials in the publish stage |

Pinning the plugin list is not pedantry: a Jenkins plugin update has silently
changed pipeline behaviour often enough that "it worked last month" is a real
failure mode.

## 4. The pipeline of record

`Jenkinsfile` at the repository root is the pipeline. It is declarative, it lives
in Git, and it is code-reviewed like anything else. Ten stages:

| # | Stage | What it does | Fails the build when |
| --- | --- | --- | --- |
| 1 | **Checkout & metadata** | `checkout scm`; resolves the git SHA, branch, `pom.xml` version and image tag; writes `build-info.properties` which is archived and baked into the image | Checkout fails |
| 2 | **Build & unit test** | `mvn -B -U clean verify -DskipITs` — compiles, runs the 31 unit + API tests, writes `jacoco.exec` | Any test fails, or compilation fails |
| 3 | **Package** | `mvn -B -DskipTests package`; archives `target/*.jar` **fingerprinted**, plus a SHA-256 checksum file | Packaging fails |
| 4 | **Docker build & tag** | Builds the image with `--build-arg APP_VERSION` and `--build-arg BUILD_REVISION=<git sha>`; tags it with the resolved version, the git SHA and `latest`; archives `image-inspect.json` | The image does not build |
| 5 | **Selenium E2E quality gate** | Starts the freshly built image as a container on `crfd-ci-net`, waits for `/api/v1/health`, starts `standalone-chrome`, runs `mvn -P selenium verify` with `SELENIUM_REMOTE_URL`; archives the JUnit XML, the failure screenshots and the application log | **Any journey fails — and that stops stages 6–10** |
| 6 | **Publish test reports** | Surefire + Failsafe JUnit XML and the JaCoCo HTML report | Never (reporting) |
| 7 | **Docker publish** | `docker.withRegistry` tags and pushes the versioned tag and the git-SHA tag to `REGISTRY` | Only when `PUBLISH_IMAGE` is set, and the push fails |
| 8 | **Deploy fresh container** | Removes the old container and runs a new one on `${APP_PORT}` with the environment's thresholds injected | Container does not start |
| 9 | **Health check & smoke test** | Waits for `/api/v1/health`, then ingests an event and reads it back through search and the summary API; archives the payloads and the container log | The app is unhealthy, or the round trip does not come back |
| 10 | **Provision node (Ansible)** | Runs `site.yml` **twice**; the second run is the idempotency evidence | Only when `RUN_ANSIBLE` is set |

### Stage 4 before stage 5 — a real bug, worth recording

The first draft built the Docker image in stage 6, *after* the Selenium gate in
stage 5. The gate then tried `docker run crfd:${IMAGE_TAG_RESOLVED}` — an image
that did not exist yet. Two things were wrong with that, and only one of them was
the crash:

1. It could not work at all.
2. Even if it had been reordered to build a *second* image after the gate, the
   gate would have been testing something other than what gets deployed. A gate
   that validates a different artefact than the one shipped is theatre.

So the image is built once, before the gate, and the gate runs **that** image. The
same image tag is what stage 8 deploys.

### Ordering principle

The pipeline is ordered so that **nothing irreversible happens before everything
verifiable has passed**: build, package, containerise, gate, then — only then —
publish and deploy. Publishing to a registry and replacing a running container are
the two irreversible steps, and both sit after the test stages.

## 5. Networking: why the pipeline never uses `localhost`

This is the subtlest thing in the pipeline and it is easy to get wrong.

Jenkins runs **inside a container**, and it talks to the **host's** Docker daemon
through the mounted socket. Every container the pipeline starts is therefore a
*sibling* of the Jenkins container, not a child. Consequences:

| Naive approach | Why it fails | What the pipeline does instead |
| --- | --- | --- |
| Selenium gate waits on `http://localhost:8080/health` | Inside the Jenkins container, `localhost` is Jenkins | Probes `http://crfd-app:8080/api/v1/health`; the app container has the network alias `crfd-app` and Jenkins is on the same bridge |
| `SELENIUM_REMOTE_URL=http://localhost:4445/wd/hub` | Same confusion — 4445 is nothing inside Jenkins | `SELENIUM_REMOTE_URL=http://crfd-selenium:4444/wd/hub`; no host port needed at all |
| Deploy stage health check on `http://localhost:${APP_PORT}` | The published port is on the *host*, not in the Jenkins container | Deploys with `--network crfd-ci-net --network-alias crfd-live` and probes `http://crfd-live:8080` |

The host port is still published in the deploy stage (`-p ${APP_PORT}:8080`) — but
for a *human* to open the build, not for the pipeline to probe it. Those are two
different audiences and conflating them is what produced the bug above.

## 6. Parameterisation (the required environment setting)

The pipeline takes parameters, so one job builds and deploys several environments
without editing anything:

| Parameter | Default | Purpose |
| --- | --- | --- |
| `ENVIRONMENT` | `dev` | `dev` / `staging` / `production`. Drives the container's `CRFD_ENVIRONMENT` badge, and turns simulation and seeding **off automatically in production** |
| `APP_PORT` | `8080` | Host port the deployed container publishes |
| `IMAGE_TAG` | *(empty)* | Empty resolves to `v<version>-<build number>` |
| `REGISTRY` | `docker.io` | Registry host |
| `REGISTRY_CREDENTIALS_ID` | `dockerhub-credentials` | Credentials id, not a secret |
| `ANSIBLE_INVENTORY` | `inventory/hosts.example.ini` | Inventory for stage 10 |
| `RUN_UNIT_TESTS` | `true` | |
| `RUN_SELENIUM` | `true` | |
| `PUBLISH_IMAGE` | `false` | Publish needs a registry and credentials, so it is opt-in |
| `DEPLOY` | `true` | |
| `RUN_ANSIBLE` | `false` | |
| `CRFD_LOW_CONFIDENCE` | `0.30` | **Environment setting**: the confidence floor the deployed container alerts on |
| `CRFD_CRITICAL_LATENCY_MS` | `1500` | **Environment setting**: the critical latency SLA |

The last two are the clearest demonstration of the requirement: the *same* image,
deployed by the *same* pipeline, alerts on different thresholds in staging and
production, because the threshold is an environment setting rather than a compile
time constant.

The `${params.ENVIRONMENT == 'production' ? 'false' : 'true'}` expressions for
simulation and seeding are a safety rail: synthetic traffic and a seeded database
are exactly what makes a demo pleasant and a production deployment wrong.

## 7. Triggered by a commit

Two triggers, deliberately both:

```groovy
triggers {
    pollSCM('H/5 * * * *')
}
```

- **`pollSCM`** — Jenkins polls the repository every five minutes (the `H/`
  spreads load so a thousand jobs do not all fire at :00) and starts a build when
  it sees a new commit on the tracked branch. This is the trigger the brief asks
  for, and it needs no inbound network access, which is why it is used rather than
  a webhook.
- **on-demand** — the job is parameterised, so a specific environment or image tag
  can be built by hand, which is how a rollback or a staging rehearsal is done.

When Jenkins is reachable from GitHub, the equivalent webhook trigger is a
two-line addition; polling is the portable choice and the one that matches a
locally hosted controller.

## 8. What is archived, and why it is the evidence

| Artefact | Stage | What it proves |
| --- | --- | --- |
| `target/*.jar` (fingerprinted) | 3 | A build produced a deployable artefact, and *which* bytes |
| `target/artifact-checksums.txt` | 3 | The artefact's SHA-256, so it can be verified after the fact |
| `build-info.properties` | 1 | version, image tag, build number, git sha, branch, environment |
| `target/image-inspect.json` | 4 | The image's labels, entrypoint and health check |
| `target/failsafe-reports/*.xml` + `target/selenium-screenshots/**` | 5 | The Selenium result, **and the screenshot of the failure if it failed** |
| `target/app-under-test.log` | 5 | What the application said while the journeys ran |
| `target/health.json`, `smoke-ingest.json`, `smoke-query.json`, `smoke-summary.json` | 9 | The deployed container really served the API |
| `target/deployed-container.log` | 9 | The deployed container's own log |
| `target/ansible-idempotency.log` | 10 | `changed=0` on the repeat run |
| `target/pipeline-result.txt` | post | A machine-readable summary of the whole build |

Fingerprinting the JAR is the detail that makes the rest usable: it records which
build produced which bytes, so "is this the artefact we tested?" becomes a
question with an answer.

## 9. Failure behaviour

`post { failure { ... } }` prints a pointed message — *"BUILD FAILED - deployment
was NOT performed"* — and removes any containers the gate left behind.

The design rule behind the whole pipeline: **a failing Selenium suite stops the
deployment.** Stage 5 comes before stages 7 and 8, so a red gate means the image
is never published and the running container is never replaced. The evidence for
that claim is `docs/11-continuous-testing-and-defect-fix.md`, where a deliberately
introduced defect turned the gate red, and the fix turned it green again — with
the failing screenshot preserved in `docs/evidence/selenium-defect-crfd-117/`.
