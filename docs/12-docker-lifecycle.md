# 12 — The Docker image and the container lifecycle

## 1. The image

`Dockerfile` is a two-stage build, because a build toolchain in a runtime image is
a liability with no upside:

```
stage 1  maven:3.9.9-eclipse-temurin-21        stage 2  eclipse-temurin:21-jre-alpine
 ├─ copy pom.xml, .mvn/, mvnw                   ├─ apk add curl tzdata
 ├─ mvnw dependency:go-offline   ← cached        ├─ addgroup/adduser crfd (system)
 ├─ copy src/                                    ├─ copy app.jar  (chown crfd)
 └─ mvnw clean package -DskipTests               ├─ USER crfd
                                                ├─ VOLUME /app/crfd-data
     → app.jar (58 MB)                          └─ HEALTHCHECK curl /api/v1/health
```

| Decision | Why |
| --- | --- |
| **Dependency layer copied before sources** | Editing one Java file must not re-download the world. This is the difference between a 15-second and a 4-minute inner loop |
| **JRE, not JDK, at runtime** | Nothing at runtime compiles Java. Removing the compiler removes an entire class of exploit and roughly 100 MB |
| **Alpine base** | Small, and the only thing the app needs from the OS is a JRE, `curl` for the health check, and `tzdata` |
| **Non-root user** | A container escape is far less interesting from `uid=100(crfd)` than from root. One `USER` line, standard practice, no excuse not to |
| **`VOLUME /app/crfd-data`** | Declares the data mount point, so the container is replaceable — which is what makes "deploy a fresh container" safe |
| **`HEALTHCHECK` on `/api/v1/health`** | Readiness, not the SLO gate. A business-metric dip must not mark a serving container unhealthy (see `docs/11` §5) |
| **`APP_VERSION` / `BUILD_REVISION` build args** | The pipeline bakes the version and the git SHA into the image, so a running container can be traced to a commit |

The image is **401 MB**, and the fact that it is JRE-only is *proved* rather than
claimed — `docs/evidence/docker-runtime-probe.txt`:

```
--- maven present? ---
ABSENT - no build tooling in the runtime image
--- javac present? ---
ABSENT - no compiler in the runtime image

--- the process does not run as root ---
uid=100(crfd) gid=101(crfd) groups=101(crfd)

--- /app contents (single fat JAR + the data volume mount point) ---
-rw-r--r-- 1 crfd crfd 58964421 Oct 18 12:17 app.jar
drwxr-xr-x 2 crfd crfd     4096 Oct 18 11:48 crfd-data
```

Seventeen packages in total, and the honest reading of that list is: a busybox, a
musl, a JRE, and enough of an OS to run them. Nothing that compiles, and no shell
utilities beyond what the health check needs.

The image identity and configuration, captured from the running container
(`docs/evidence/docker-inspect.txt`):

```
State=running Running=true Health=healthy Restarts=0 Image=crfd:1.0.0
Ports={"8080/tcp":[{"HostIp":"0.0.0.0","HostPort":"18080"}]}
Mounts=[{"Type":"volume","Name":"crfd-data","Destination":"/app/crfd-data","RW":true}]
Env=[... "CRFD_ENVIRONMENT=docker", "CRFD_SIMULATION_ENABLED=true",
     "APP_VERSION=1.0.0-SNAPSHOT", "BUILD_REVISION=local" ...]
```

`Restarts=0` and `Health=healthy` are the two lines that matter: the JVM came up
once and stayed up, and the container's own health check agrees.

## 2. Build, tag, run

```bash
# build
docker build -t crfd:1.0.0 -t crfd:latest .

# run, with the data volume and the environment
docker run -d --name crfd-app \
  -p 18080:8080 \
  -e CRFD_ENVIRONMENT=docker \
  -e CRFD_SIMULATION_ENABLED=true \
  -e CRFD_SEED_ON_STARTUP=true \
  -v crfd-data:/app/crfd-data \
  crfd:1.0.0

# confirm
docker ps
docker inspect --format='{{.State.Health.Status}}' crfd-app     # -> healthy
```

```text
REPOSITORY   TAG       IMAGE ID       SIZE      CREATED
crfd         1.0.0     3148ed71ea62   401MB     2 minutes ago
crfd         latest    3148ed71ea62   401MB     2 minutes ago

NAMES      IMAGE        STATUS                   PORTS
crfd-app   crfd:1.0.0   Up 2 minutes (healthy)   0.0.0.0:18080->8080/tcp
```

Port 18080 rather than 8080 because 8080 is frequently occupied on a developer
machine. The mapping is a deployment choice, not a property of the image — which
is exactly why the image exposes 8080 and nothing about the image knows about
18080.

### Tagging

| Tag | Meaning |
| --- | --- |
| `crfd:1.0.0` | The release tag. Immutable by convention; what gets deployed |
| `crfd:latest` | A convenience pointer for local use. Never deployed by the pipeline |
| `crfd:<git-sha>` | Built by the pipeline. The only tag that answers "which commit is this?" unambiguously |

The pipeline deploys the **version tag** and publishes both the version tag and the
SHA tag, which is the detail that makes "is this the artefact we tested?" a
question with an answer.

## 3. The lifecycle, exercised deliberately

Stopping, starting, restarting and removing a container are the operations that get
skipped until an incident needs them. `scripts/docker-lifecycle.sh` and its
PowerShell twin run them in order and capture what happened:

```powershell
scripts/docker-lifecycle.ps1 all       # build -> tag -> run -> health -> logs
                                       # -> exec probe -> restart -> stop -> start
scripts/docker-lifecycle.ps1 logs
scripts/docker-lifecycle.ps1 health
scripts/docker-lifecycle.ps1 exec      # prove it is JRE-only, non-root
scripts/docker-lifecycle.ps1 restart
scripts/docker-lifecycle.ps1 remove
scripts/docker-lifecycle.ps1 clean     # remove container and image
```

| Action | Command | What it demonstrates |
| --- | --- | --- |
| `build` | `docker build` | Reproducible from the `Dockerfile` alone |
| `tag` | `docker tag` | The same image carries a version tag and `latest` |
| `run` | `docker run -d ... -p 18080:8080 -v crfd-data:...` | Port mapping and volume binding |
| `ps` | `docker ps` | It is running |
| `health` | poll `/api/v1/health` on 18080 | The application is serving, not merely started |
| `logs` | `docker logs --tail 40` | Logs are the first diagnostic |
| `exec` | `docker exec ... id`, `java -version`, `which java` | Non-root; JRE only; no `mvn`/`javac` |
| `stop` | `docker stop` | Graceful shutdown (`server.shutdown: graceful`) |
| `start` | `docker start` | The **same container** resumes, `Restarts` still a meaningful number |
| `restart` | `docker restart` | A restart is not a redeploy; it does not pick up new code |
| `remove` | `docker rm -f` | Removing the container does **not** touch the volume |
| `rmi` / `clean` | `docker rmi` | Image removal, and the full teardown |

## 4. Why the volume is the important part

The lifecycle above is only safe because of one line in `docker run`:

```
-v crfd-data:/app/crfd-data
```

Event data — and the H2 file database that holds it — lives on a named volume, not
inside the container. The consequences:

1. `docker rm -f crfd-app` loses **no data**.
2. A fresh container can be started from a new image and find the previous data.
3. Therefore "deploy a fresh container" is a safe operation rather than a gamble.

That third point is the whole reason the deployment strategy works. A deployment is
`rm -f` followed by `run`, and it would be catastrophic if the data lived in the
container's writable layer. Because it does not, the crude strategy is also the
correct one.

`docs/evidence/docker-inspect.txt` confirms the mount is a named volume with
`"RW": true` at `/app/crfd-data`, matching the `H2` datasource URL
`jdbc:h2:file:/app/crfd-data/crfd`.

## 5. Compose: the two-container stack

```bash
docker compose up -d --build
# Dashboard through Nginx : http://localhost:8081
# Application direct      : http://localhost:8080
```

```yaml
services:
  app:    image: crfd:1.0.0   ports: ["8080:8080"]   volumes: [crfd-data:/app/crfd-data]
          healthcheck: curl -fsS http://localhost:8080/api/v1/health
  nginx:  image: nginx:1.27-alpine
          ports: ["8081:80"]
          depends_on: { app: { condition: service_healthy } }
networks:
  crfd-net: { name: crfd-net }      # pinned
volumes:
  crfd-data:
```

Three deliberate choices:

**`condition: service_healthy`.** Nginx does not start until the application
reports healthy. Without it, Nginx comes up first, caches a failed upstream
resolution, and serves `502` until someone notices. The health check is what makes
`depends_on` mean something.

**The network name is pinned** to `crfd-net`. Docker Compose otherwise generates
`<project>_<name>`, which changes with the directory name and makes the Selenium
overlay (`infra/selenium/docker-compose.selenium.yml`) unable to join reliably.

**The health check targets `/api/v1/health`** — readiness. Not the SLO gate. See
`docs/11` §5 for what happened when that distinction was missing.

## 6. What the Docker work proves, against the brief

| Requirement | Where it is satisfied |
| --- | --- |
| Dockerfile | `Dockerfile` — multi-stage, non-root, JRE-only, health check |
| Build the image | `docker build -t crfd:1.0.0 .` — `docs/evidence/docker-images.txt` |
| Tag | `crfd:1.0.0`, `crfd:latest`, and `crfd:<git-sha>` from the pipeline |
| Run with port mapping | `-p 18080:8080` → `0.0.0.0:18080->8080/tcp` |
| Logs | `docker logs -f crfd-app`; archived by the pipeline as `deployed-container.log` |
| Stop / restart / remove | `scripts/docker-lifecycle.*` exercises each, in order |
| Data survives removal | Named volume `crfd-data`, confirmed in `docker-inspect.txt` |
| Versioned image for the pipeline | Stage 4 builds `crfd:<version>-<build>` + `crfd:<sha>` |
| Publish | Stage 7 pushes to the registry via `docker.withRegistry` |
| Deploy a fresh container | Stage 8 |

## 7. Known limitations

- **Single-host, single-container deployment.** No swarm, no Kubernetes. Correct
  for the MVP's scale, and the `VOLUME`/env-var design means the container is
  already portable to an orchestrator when that becomes necessary.
- **The H2 file database is single-writer.** Fine at MVP volume; MySQL is the
  multi-writer shape and is what the `mysql` profile and `docker-compose.mysql.yml`
  exist for.
- **`.dockerignore` matters more than it looks.** Without it, a `COPY src ./src`
  also copies `target/` and any local `crfd-data/`, which both bloats the context
  and can leak local state into an image layer.
- **No image signing or SBOM.** The pipeline records the SHA-256 of the JAR but
  does not use Cosign or generate an SBOM. Noted in `docs/16`.
