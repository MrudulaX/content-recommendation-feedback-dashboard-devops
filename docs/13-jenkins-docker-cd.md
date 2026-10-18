# 13 — Jenkins + Docker: continuous deployment

This is the deliverable the brief calls *"Jenkins-Docker Continuous Deployment"*:
Jenkins produces a **versioned** image, publishes it, and deploys a **fresh**
container — with the Selenium gate standing between the build and the deploy.

`docs/08` is the Jenkins controller and the stage list. `docs/12` is the image and
the container lifecycle by hand. This document is the join: what the pipeline does
with Docker, end to end.

## 1. The chain

```
   commit on develop
         │  (pollSCM, every 5 min)
         ▼
   ① checkout            git sha, version, image tag          → build-info.properties
         ▼
   ② build & unit test   mvn -B clean verify -DskipITs        → 31 tests, jacoco.exec
         ▼
   ③ package             mvn -B -DskipTests package           → target/*.jar + sha256
         ▼
   ④ docker build & tag  --build-arg BUILD_REVISION=$GIT_SHA  → crfd:1.0.0-42
         ▼                                                       crfd:<sha>  crfd:latest
   ⑤ SELENIUM GATE       the built image + standalone-chrome   → 5/5, or STOP
         ▼                on crfd-ci-net
   ⑥ test reports        surefire + failsafe + jaCoCo html
         ▼
   ⑦ docker publish      docker.withRegistry → push version + sha tags
         ▼
   ⑧ deploy              docker rm -f crfd-app; docker run … crfd:1.0.0-42
         ▼
   ⑨ health & smoke      /api/v1/health, then write→read→summary
         ▼
   ⑩ ansible (optional)  site.yml twice; second run must be changed=0
```

Everything to the right of stage ⑤ depends on ⑤ having passed. The two
irreversible actions — publishing an image and replacing a running container — are
stages ⑦ and ⑧, and both sit after the gate.

## 2. Versioned images: what "versioned" has to mean

The brief asks for a *versioned* image. A tag like `latest` is a name, not a
version — it points at different bytes on different days, which makes rollback
meaningless and incident timelines unverifiable.

The pipeline resolves the tag in stage ① and uses it consistently through stages ④,
⑤, ⑦ and ⑧:

| Variable | Resolves to | Example |
| --- | --- | --- |
| `BUILD_VERSION` | the `pom.xml` version | `1.0.0` |
| `IMAGE_TAG_RESOLVED` | `IMAGE_TAG` parameter, or `v<version>-<build number>` | `v1.0.0-42` |
| `GIT_SHA` | the commit Jenkins checked out | `9c1f3ab` |

and builds three tags for the same image:

```
crfd:v1.0.0-42      the release. Immutable by convention. This is what deploys.
crfd:9c1f3ab        the only tag that unambiguously answers "which commit is this?"
crfd:latest         a convenience pointer. Never deployed.
```

The image also *contains* its identity, which is what makes a running container
traceable after the fact:

```groovy
--build-arg APP_VERSION=${env.BUILD_VERSION} \
--build-arg BUILD_REVISION=${env.GIT_SHA}
```

```text
Env=[... "APP_VERSION=1.0.0-SNAPSHOT", "BUILD_REVISION=local" ...]
```

So `docker inspect crfd:1.0.0` answers the question `docker ps` cannot: not just
"which image is running" but "which source revision produced it".

## 3. Build once, gate that build, deploy that build

The most important property of this pipeline is negative: **there is exactly one
image in the whole run.**

`docs/08` §4 records the bug that produced this rule. The first draft built the
image in a stage *after* the Selenium gate, so the gate tried to run an image that
did not exist yet. The fix was not merely to move the build earlier; it was to
recognise *why* the ordering was wrong. A gate that validates one artefact while a
different artefact is shipped is theatre: it consumes the time of running tests and
returns none of the confidence.

The rule that came out of it:

```
stage ④ builds crfd:<tag>
stage ⑤ gates  crfd:<tag>
stage ⑦ pushes crfd:<tag>
stage ⑧ deploys crfd:<tag>
        └── the same image object, referenced by the same tag, four times
```

## 4. Publishing

```groovy
stage('Publish image') {
    when { expression { params.PUBLISH_IMAGE } }
    steps {
        script {
            docker.withRegistry("https://${params.REGISTRY}",
                                "${params.REGISTRY_CREDENTIALS_ID}") {
                sh "docker push ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}"
                sh "docker push ${env.IMAGE_NAME}:${env.GIT_SHA}"
            }
        }
    }
}
```

Four things are deliberate:

**It is opt-in** (`PUBLISH_IMAGE`, default `false`). Publishing needs a registry and
credentials; a pipeline that cannot run without them cannot run on a laptop.

**Credentials are referenced by id, never by value.** `dockerhub-credentials` is a
JCasC-declared credentials entry; the secret is injected at runtime. Nothing secret
is in the repository or in the console log.

**Both tags are pushed.** The version tag is what a deployment refers to; the SHA
tag is what an investigation refers to. Pushing only one of them makes one of those
two jobs impossible.

**`docker push`, not `docker build` again.** Rebuilding here would produce a
different image digest from the one the gate tested. Same trap as §3.

The local alternative is a `registry:2` container on `localhost:5001`, started by
`infra/jenkins/docker-compose.jenkins.yml`, so the whole publish path can be
exercised without a Docker Hub account.

## 5. Deploying a fresh container

```groovy
sh "docker network create ${env.NETWORK_NAME} || true"
sh "docker rm -f ${env.CONTAINER_NAME} || true"
sh """
    docker run -d --name ${env.CONTAINER_NAME} \
        --restart unless-stopped \
        --network ${env.NETWORK_NAME} \
        --network-alias crfd-live \
        -p ${params.APP_PORT}:8080 \
        -e CRFD_ENVIRONMENT=${params.ENVIRONMENT} \
        -e CRFD_SIMULATION_ENABLED=${params.ENVIRONMENT == 'production' ? 'false' : 'true'} \
        -e CRFD_SEED_ON_STARTUP=${params.ENVIRONMENT == 'production' ? 'false' : 'true'} \
        -e CRFD_LOW_CONFIDENCE=${params.CRFD_LOW_CONFIDENCE} \
        -e CRFD_CRITICAL_LATENCY_MS=${params.CRFD_CRITICAL_LATENCY_MS} \
        ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}
"""
```

| Flag | Reason |
| --- | --- |
| `--network ${NETWORK_NAME}` + `--network-alias crfd-live` | So the pipeline can probe the deployed container **by name**. `localhost` inside the Jenkins container is Jenkins — see `docs/08` §5 |
| `-p ${APP_PORT}:8080` | For a **human** to open the build. Not used by the pipeline |
| `--restart unless-stopped` | Survives a Docker daemon restart; does not fight a deliberate `docker stop` |
| `-e CRFD_ENVIRONMENT`, `CRFD_LOW_CONFIDENCE`, `CRFD_CRITICAL_LATENCY_MS` | **The environment settings.** The same image alerts on different thresholds per environment — the clearest demonstration of the required parameterisation |
| `… == 'production' ? 'false' : 'true'` | Synthetic traffic and a seeded database are what make a demo pleasant and a production deployment wrong. The pipeline enforces the distinction instead of relying on memory |
| `docker rm -f` before `docker run` | Replace, never mutate. The new container is created from an immutable versioned image, so "which code is running?" always has one answer |
| `-v ${DATA_VOLUME}:/app/crfd-data` | **Essential, and easy to miss.** The image declares `VOLUME /app/crfd-data`, so a `docker run` without `-v` creates an *anonymous* volume — which `docker rm` then destroys along with the container. Replace-and-run would silently wipe the event history on every deployment. Mounting the named volume is what makes the crude replace safe |

## 6. Verifying the deployment

```groovy
def base = "http://crfd-live:8080"
sh "bash scripts/wait-for-health.sh '${base}/api/v1/health' 120"

// readiness, captured as evidence
sh "curl -fsS ${base}/api/v1/health | tee target/health.json"

// a real write, then a real read
sh """curl -fsS -X POST ${base}/api/v1/events \\
        -H 'Content-Type: application/json' \\
        -d '{"userId":"U-SMOKE","itemId":"ITM-SMOKE","itemTitle":"Smoke item",
             "source":"TRENDING","surface":"HOME_FEED","eventType":"CLICK","score":0.8}' \\
     | tee target/smoke-ingest.json"""

sh "curl -fsS '${base}/api/v1/events?q=ITM-SMOKE' | tee target/smoke-query.json"
sh "grep -q ITM-SMOKE target/smoke-query.json"

sh "curl -fsS '${base}/api/v1/metrics/summary?windowHours=24' | tee target/smoke-summary.json"
```

The four checks are chosen so that each catches something the previous one cannot:

1. **`wait-for-health.sh`** — the container is up but the application may never have
   finished starting, or a hard dependency may be down.
2. **`/api/v1/health` payload** — archived, so the health state of *that build* is
   on the record rather than merely asserted.
3. **write → read** — a dashboard that renders but cannot ingest is broken in the
   one way that matters. A readiness endpoint can be green while the write path is
   broken by a missing index or a rejected schema.
4. **`/api/v1/metrics/summary`** — the read model can aggregate.

Note that check 1 gates on `/api/v1/health`, the **readiness** endpoint, which
answers `200` for `DEGRADED`. A pipeline success-rate dip must not fail an
otherwise good deployment; `docs/11` §5 is the story of what happens when that
distinction is missing, and it is the reason the deployed build is allowed to sit
at a success rate that hovers around its `0.95` objective.

## 7. The gate, demonstrated rather than asserted

The claim "a failing test stops the deployment" is worth exactly as much as the
evidence behind it. There is evidence:

- **The failure.** A journey failed against a build, and the failing run's
  screenshot, page source and diagnostic text were preserved in
  `docs/evidence/selenium-defect-crfd-117/`. Because the gate is stage ⑤ and the
  publish/deploy stages are ⑦/⑧, that run published nothing and replaced nothing.
- **The fix.** The defect write-up is `docs/11` §§3–4.
- **The rerun.** 5/5 green, twice, in two environments:
  `docs/evidence/selenium-remote-run.log`.

## 8. Rollback

Two mechanisms, for two situations.

**Immediate — the image is still on the daemon:**

```bash
docker rm -f crfd-app
docker run -d --name crfd-app --network crfd-ci-net --network-alias crfd-live \
    -p 8080:8080 -v crfd-data:/app/crfd-data \
    crfd:<previous-tag>
```

This is why versioned tags exist. `latest` would make this step a rebuild from an
unknown revision.

**Recorded — where more than one node is involved:** the Ansible `crfd_app` role
writes the outgoing release to `/etc/crfd/previous-release.txt` only when the
version actually changes, and appends to `/etc/crfd/release-history.log`. Because
the outgoing release is recorded, a rollback leaves the node able to roll *forward*
again — recovery is symmetric rather than a one-shot fix. Evidence:

```text
previous-release.txt: 1.0.0
release-history.log:
  2026-10-18T12:44:43Z  0.1.0 -> 1.0.0  (by ansible, host=crfd-node)
  2026-10-18T12:45:20Z  1.0.0 -> 0.1.0  (by ansible, host=crfd-node)
```

Detail: `docs/14-ansible-provisioning.md`.

## 9. Testing the pipeline itself

A pipeline that has never been run is a script, not evidence — so this is
documented honestly:

| Aspect | Status |
| --- | --- |
| Every stage command | **Executed and captured** — `mvn clean verify` (31 tests), `mvn -P selenium verify` (5 journeys), `docker build`, `docker run -p`, health poll, write→read smoke, all five Ansible playbooks with `changed=0` on the second run |
| Stage **ordering** | Verified by inspection after fixing the build-after-gate bug. The ordering is the property that cannot be proved by running stages individually |
| Full Jenkins run | **Not executed end to end.** The controller is defined as code (`infra/jenkins/`) and requires a GitHub remote, which this environment has no credentials for. `docs/16` records this as a limitation; the exact commands to run it are in the README |
| Networking assumptions | Verified independently: containers on `crfd-ci-net` address each other by alias, confirmed by the containerised Selenium run using `http://crfd-app:8080` behind the hub |

The distinction matters. Every command in the pipeline has been run and its output
captured; what has not been demonstrated is Jenkins *orchestrating* them in one
click. Claiming otherwise would be the kind of overstatement that makes an entire
report untrustworthy.

## 10. Summary against the brief

| Requirement | Where |
| --- | --- |
| Jenkins builds a **versioned** Docker image | Stage ④ — `crfd:v1.0.0-42` (version) + `crfd:<sha>` (commit), both built from one `docker build` |
| Publishes to Docker Hub / local registry | Stage ⑦ — `docker.withRegistry` + `docker push`; `registry:2` on `localhost:5001` for local use |
| Deploys a **fresh** container | Stage ⑧ — `docker rm -f` then `docker run`, never a mutate-in-place |
| The deploy uses the **gated** image | One image object referenced by one tag across stages ④⑤⑦⑧ |
| Environment settings | `ENVIRONMENT`, `APP_PORT`, `CRFD_LOW_CONFIDENCE`, `CRFD_CRITICAL_LATENCY_MS`, all pipeline parameters |
| A failing test stops the deployment | Stage ⑤ precedes ⑦ and ⑧; demonstrated with real failure evidence |
| Port mapping, logs, lifecycle | Stages ⑧/⑨ and `scripts/docker-lifecycle.*`; `docs/12` |
| Rollback | Previous version tag; recorded release + `previous-release.txt` for the Ansible path |
