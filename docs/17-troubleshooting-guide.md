# 17 — Troubleshooting guide

Every entry below is a problem that **actually occurred while building this
project**, with the message as it appeared. That is why the list is specific rather
than generic: these are the failures this system produces, not a catalogue of
everything that can go wrong with Java.

Each entry has the same shape: the message, the cause, the fix, and where the full
account lives. Entries marked **⚠ historical** describe a defect that is *fixed in the
repository* - they are here because knowing what the symptom looked like is how you
recognise a regression.

---

## 0. Two rules before anything else

**Rule 1 - read the whole message, then read the line before it.** Twice in this
project the most important line was a `WARNING` printed before the error, and the error
named a completely different problem. Ansible's `the role 'common' was not found` was
caused by a world-writable directory warning on the previous line; the YAML file was
never wrong.

**Rule 2 - find the layer.** Most confusion here came from assuming the wrong layer.
"Do the tests fail?" and "is the application up?" are different questions with different
checks:

```bash
docker inspect --format '{{.State.Health.Status}}' crfd-app   # container
curl -fsS http://localhost:18080/api/v1/health                # application readiness
curl -fsS http://localhost:18080/api/v1/health/slo            # business SLO
docker logs --tail 100 crfd-app                               # what it actually said
```

If those four disagree, the disagreement *is* the diagnosis. Section 3 exists because
they disagreed in this project in a way that is genuinely counter-intuitive.

---

## 1. Build and startup

### 1.1 `UnsupportedClassVersionError` on startup

```text
java.lang.UnsupportedClassVersionError: com/crfd/dashboard/DashboardApplication
has been compiled by a more recent version of the Java Runtime (class file version 65.0),
this version of the Java Runtime only recognizes class file versions up to 61.0
```

**Cause.** The project targets **Java 21** (class file 65); the `java` on `PATH` is
Java 17 (61). Very common on a machine with two JDKs and `JAVA_HOME` pointing at the
wrong one.

**Fix.**

```bash
java -version          # expect 21
mvn -v | head -3       # the "Java version:" line must also be 21
# Windows: set JAVA_HOME to the Temurin 21 install, then reopen the shell
```

Maven has its own view of `JAVA_HOME`. A build that compiles fine and an application
that refuses to start points here.

### 1.2 `./mvnw` fails: no such file, or cannot find the wrapper

**Cause.** On Windows use `mvnw.cmd`, not `./mvnw`. Inside Git Bash the reverse is true.

**Fix.** `.\mvnw.cmd -B clean package` on PowerShell; `./mvnw -B clean package` in bash.
If the wrapper itself is missing, `.mvn/wrapper/maven-wrapper.properties` names the
distribution and the script downloads Maven on first use - it needs network access, so a
failure here is usually a proxy, not a corrupt wrapper.

### 1.3 `bash: ./scripts/wait-for-health.sh: /bin/bash^M: bad interpreter`

**Cause.** CRLF line endings on a shell script. Git for Windows checked the file out
with CRLF (or an editor saved it that way), and the kernel reads `\r` as part of the
interpreter path.

**Fix.** This repository pins the policy in `.gitattributes` (`* text=auto eol=lf`,
with `*.sh text eol=lf`) so a fresh checkout is correct:

```bash
git check-attr text eol -- scripts/wait-for-health.sh
# scripts/wait-for-health.sh: text: set
# scripts/wait-for-health.sh: eol: lf
```

If a working copy is already wrong: `git add --renormalize .` then re-checkout, or
`dos2unix scripts/*.sh`.

### 1.4 `Port 8080 was already in use`

**Cause.** Something else owns 8080 - very common, and on this machine it was an
unrelated process (PID 7856).

**Fix.** Never kill a process you cannot identify. Move the application:

```bash
SERVER_PORT=18081 java -jar target/*.jar
# or, in the container:
docker run -d -p 18080:8080 crfd:1.0.0
```

This is why every command in this report uses **18080** for the container and 8080 only
inside it. `docs/18` uses the same ports.

---

## 2. The application and the API

### 2.1 `409 Conflict` when posting an event

```json
{"status":409,"error":"Conflict","message":"Event EVT-7F3A91C4 has already been ingested","path":"/api/v1/events"}
```

**Cause.** Not a bug. Ingestion is idempotent: the same `eventId` twice is a replay, and
a replay is rejected. A `DUPLICATE_EVENT` alert is raised first, in its own transaction,
so the evidence survives the rejection.

**Fix.** None - this is the contract. Generate a new key, or omit `eventId` and let the
server mint one. If you are seeing this from a client that retries, the client needs an
idempotency decision, not a looser server.

### 2.2 An event never leaves `RECEIVED`

**Cause.** The pipeline is asynchronous by default (`crfd.processing.async-enabled=true`),
so `RECEIVED` means "queued", not "stuck". In tests it is set to `false` for determinism.

**Fix.** Wait a moment and re-read, or set `CRFD_ASYNC_ENABLED=false` for deterministic
behaviour. If it is still `RECEIVED` after a restart, check `docker logs crfd-app` for
the pipeline scheduler - a scheduler that failed to start leaves every event queued.

### 2.3 An event is `QUARANTINED` and there is a `SCHEMA_MISMATCH` alert

**Cause.** Deliberate. A payload arrived with no `itemId`. Rejecting it would lose data;
discarding it silently would be worse.

**Fix.** None - the event is stored and the alert is the notification. If it is
unexpected, fix the *producer*, which is what the alert is for.

### 2.4 `GET /api/v1/nope` returns 500 ⚠ historical

**Cause.** `CRFD-121`. `NoResourceFoundException` was falling into the catch-all
handler. Fixed.

**Fix.** If it reappears, the handler for `NoResourceFoundException` has been removed
from `GlobalExceptionHandler`. The regression test
`unmappedUrlReturnsNotFoundInsteadOfServerError` is what should catch it.

### 2.5 `GET` on the status endpoint returns 500 instead of 405 ⚠ historical

**Cause.** `CRFD-121`, same root cause - `HttpRequestMethodNotSupportedException`
reaching the catch-all.

**Fix.** Regression test `wrongVerbReturnsMethodNotAllowed`. A 405 is also where the
`Allow` header comes from; a 500 hides it, so a client cannot even discover the right
verb.

### 2.6 The CSV export returns fewer rows than the table shows

**Cause.** The export is capped at 5000 rows. The table pages.

**Fix.** None - the cap is deliberate (an export that streams the whole table is a
denial-of-service tool with a download link, PR #3). Narrow the filter.

### 2.7 `?size=1000` returns 200 rows

**Cause.** `size` is clamped to 1-200.

**Fix.** Page with `page`. This was a review change on PR #3.

### 2.8 A search for `AB_CD` matches more than expected

**Cause.** `%` and `_` reach `LIKE` and act as SQL wildcards. Known limitation B3.

**Fix.** None at present; recorded in `docs/16`.

---

## 3. Readiness, health and SLO

**Read this section before concluding "the application is down".** It caused two of the
project's most misleading failures.

### 3.1 The container is `unhealthy` but the application serves every request perfectly

```bash
$ docker inspect --format '{{.State.Health.Status}}' crfd-app
unhealthy
$ curl -s http://localhost:18080/api/v1/events | head -c 80
{"content":[{"eventId":"EVT-...   ← working fine
```

**Cause.** ⚠ **Historical, and the most instructive failure in the project.** The single
health endpoint returned 503 whenever anything was off - *including a pipeline success
rate below its 0.95 objective*. Seeding the database produces about 0.93-0.96, so a
fully functional instance reported unhealthy, the container `HEALTHCHECK` failed, and
`depends_on: service_healthy` meant **Nginx never started**.

**Fix.** Two endpoints, two questions (`docs/11` §5):

| Endpoint | Answers | 503 when | Read by |
| --- | --- | --- | --- |
| `/api/v1/health` | can this instance serve traffic? | a hard dependency is `DOWN` | container health check, Nginx, the deploy gate |
| `/api/v1/health/slo` | is the pipeline meeting its objective? | the SLO is breached | monitoring |

```bash
curl -i http://localhost:18080/api/v1/health      # 200 for UP *and* DEGRADED
curl -i http://localhost:18080/api/v1/health/slo  # 503 when BREACHED - correct
```

**If you see this symptom again**, something has re-pointed readiness at the SLO. Check
the Dockerfile `HEALTHCHECK`, `infra/nginx/`, the Jenkins deploy stage and
`infra/ansible/roles/healthcheck/`. The rule: *never let a business metric decide whether
a node is able to serve traffic.*

### 3.2 `/api/v1/health/slo` returns 503 in a perfectly good deployment

**Cause.** Correct behaviour. The pipeline success rate is below `0.95`
(`CRFD_MINIMUM_SUCCESS_RATE`).

**Fix.** Decide which question you are asking. If you are a load balancer, ask `/health`.
If you are monitoring, this is a real alert - and the useful next step is the breakdown,
not the number:

```bash
curl -s 'http://localhost:18080/api/v1/metrics/summary?windowHours=24'
```

### 3.3 `/health` says `DEGRADED` in tests but `BREACHED` in the container

**Cause.** Different thresholds. The test profile sets
`crfd.thresholds.minimum-success-rate: 0.50`; the deployed application runs at `0.95`.

**Fix.** Expected. Just be aware the same code reports differently - which is the point
of externalised configuration, and a good way to demonstrate it.

### 3.4 The Ansible play fails: `crfd-node reports status=DEGRADED` ⚠ historical

**Cause.** The health-check playbook asserted `status == 'UP'` - the same mistake as
§3.1, made again in a different place, which is why it is worth an entry of its own.

**Fix.** The assertion accepts `UP` or `DEGRADED` (serving), and the SLO is reported
*without* failing:

```text
HEALTH crfd-node -> status=UP version=1.0.0-SNAPSHOT container=running
                    slo=WITHIN_OBJECTIVE (successRate=0.9595)
```

Assert the thing that must block a deployment; report the thing that must not.

---

## 4. Docker

### 4.1 Deploying wipes all the data

```bash
docker rm -f crfd-app
docker run -d --name crfd-app -p 18080:8080 crfd:1.0.0     # ← no -v
```

**Cause.** ⚠ A genuine trap. The image declares `VOLUME /app/crfd-data`, so a `docker
run` with no `-v` creates an **anonymous** volume - and `docker rm` destroys anonymous
volumes with the container. Every replace-and-run therefore silently wipes the event
history. This was caught in review (PR #12) and fixed in both the pipeline and the
Ansible role.

**Fix.** Always mount the named volume:

```bash
docker run -d --name crfd-app -p 18080:8080 -v crfd-data:/app/crfd-data crfd:1.0.0
```

**Detect it:** `docker inspect crfd-app --format '{{json .Mounts}}'` - look for
`"Name":"crfd-data"`. If `"Name"` is a 64-character hash, you are on an anonymous volume
and the next `docker rm` will take it.

### 4.2 `docker: Error response from daemon: unable to apply cgroup configuration: cannot enter cgroupv2 ... with domain controllers`

**Cause.** The node's Docker engine is itself in a container (`docker:dind`). A nested
engine cannot apply cgroup controller configuration, so `--memory` / `--cpus` fail.

**Fix.** Make the limits opt-in and blank them for the demo node
(`infra/ansible/inventory/demo-overrides.yml`); the production defaults keep them. This
is a property of nested Docker, not a playbook bug - see `docs/14` §7b.

### 4.3 `docker build` takes four minutes for a one-line change

**Cause.** The dependency layer was invalidated because the sources were copied before
dependencies were resolved.

**Fix.** Resolve dependencies first, then copy `src`:

```dockerfile
COPY pom.xml .
RUN ./mvnw -B dependency:go-offline
COPY src ./src
```

Already the shape of this `Dockerfile`; a regression here is very noticeable.

### 4.4 `Bind for 0.0.0.0:18080 failed: port is already allocated`

**Cause.** A container (often a previous `crfd-app`) still holds the port.

**Fix.**

```bash
docker ps -a --filter name=crfd-app
docker rm -f crfd-app
```

`docker stop` leaves the container; only `rm` frees the name.

### 4.5 `docker compose up` starts Nginx and it 502s

**Cause.** Nginx started before the application was ready - `depends_on` without a
healthy condition only waits for the container to be *created*.

**Fix.** Condition on health:

```yaml
depends_on:
  crfd-app:
    condition: service_healthy
```

Already in `docker-compose.yml`. If you see a 502, check that the application's
`HEALTHCHECK` is not the one from §3.1.

---

## 5. Selenium

### 5.1 `StaleElementReferenceException` - intermittently, one run in five

**Cause.** The dashboard table auto-refreshes. A read can lose its element between
locating it and reading it. This also **hid a real defect**: the flakiness meant the
`EVT-` bug (`CRFD-117`) failed one time in five instead of every time.

**Fix.** The test support re-locates and retries on `StaleElementReferenceException`
and `NoSuchElementException`; it does not sleep longer. Making the test *reliable*
revealed the bug - which is the whole argument for the Selenium gate (`docs/11`).

**Do not** fix this by increasing the implicit wait: that hides the cause and slows
every run.

### 5.2 `Connection refused` or `localhost` refused inside the browser container

**Cause.** `localhost` inside the Selenium container is the *Selenium container*, not
your application. With a remote browser, the base URL must resolve on the Docker network.

**Fix.**

```bash
SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub \
./mvnw -B -P selenium verify \
    -Dselenium.baseUrl=http://crfd-app:8080 \
    -Dselenium.headless=true
```

`SELENIUM_REMOTE_URL` is reached from the *host* (so `localhost` is right); the base URL
is reached from the *browser* (so the network alias is right). Getting the two the same
way round is the mistake.

### 5.3 `session not created: This version of ChromeDriver only supports Chrome version N`

**Cause.** A locally installed Chrome and the driver have drifted apart. The local
Chrome (155) is also unreliable for rendering here.

**Fix.** Use the pinned container - this is why the project does:

```bash
docker run -d --name crfd-selenium -p 4444:4444 --network crfd-net \
    selenium/standalone-chrome:4.25.0
```

`-Dselenium.browser=auto` picks it up. The image tag is deliberately pinned so the
browser is a known quantity.

### 5.4 The suite passes locally and fails in the container

**Check, in order:** (1) the base URL (§5.2); (2) headless mode, which changes layout -
`-Dselenium.headless=true` locally to reproduce; (3) whether the failing run's screenshot
exists, because a failure dumps one:

```text
target/failsafe-reports/   and   docs/evidence/selenium-defect-crfd-117/
```

The preserved screenshot, page source and JUnit text from the one genuine failure are
the model to follow when triaging: the PNG says *what the user saw*, the HTML says *what
the DOM was*, the text says *what the assertion wanted*.

### 5.5 A journey passes alone and fails in the full run

**Cause.** Shared state between journeys - the table is still filtering from J1 when J3
runs.

**Fix.** Each journey starts from a known state and asserts on `data-*` identity rather
than on position or text. If you add a journey, do not assume a clean filter.

---

## 6. Jenkins

### 6.1 A build step cannot reach the application at `localhost`

**Cause.** **Jenkins is itself a container.** It uses the host Docker socket, so the
containers it starts are *siblings*, and `localhost` inside a build step is Jenkins.

**Fix.** Address siblings by network alias on the pinned network:

```groovy
sh "curl -fsS http://crfd-app:8080/api/v1/health"
sh "docker run --network crfd-ci-net --network-alias crfd-live ..."
```

`crfd-app`, `crfd-selenium`, `crfd-live`. Host port publishing (`-p 18080:8080`) exists
only so a human can open the build in a browser; the pipeline never uses it.

### 6.2 `docker: command not found` inside a build

**Cause.** The Docker CLI is not in the Jenkins image, or the socket is not mounted.

**Fix.** Both are needed: the CLI in `infra/jenkins/` and
`-v /var/run/docker.sock:/var/run/docker.sock` in
`docker-compose.jenkins.yml`. The socket alone gives you nothing to type into.

### 6.3 The gate runs against an image that does not exist ⚠ historical

```text
Unable to find image 'crfd:v1.0.0-42' locally
```

**Cause.** A real bug found by reading, not by running: the image was built in a stage
*after* the Selenium gate. The deeper problem was not the order but the *design* - a gate
that validates one artefact while a different artefact ships is theatre.

**Fix.** Build once, gate that build, publish and deploy that build - one image, one tag,
four stages (`docs/13` §3). If you reorder the `Jenkinsfile`, this is the invariant to
preserve.

### 6.4 The pipeline parameters do not appear in the job

**Cause.** The seed job declared them too, and the two copies drifted; or the pipeline
was loaded lightweight, so it never read the `Jenkinsfile`.

**Fix.** Parameters live in the `Jenkinsfile` **only**, and the seed job uses
`lightweight(false)` so the file is read. Never redeclare them in the seed job
(`infra/jenkins/jenkins.yaml`).

### 6.5 A plugin update breaks the build after a rebuild

**Cause.** The plugin list tracked `latest`.

**Fix.** `infra/jenkins/plugins.txt` pins versions. An unpinned list means a controller
rebuilt after a disk failure can differ from the one the pipeline was proven against -
the same class of mistake as deploying `latest` of the application.

### 6.6 The pipeline never starts

**Cause.** The checkout stage needs a real remote; this repository's `origin` is a local
bare repository and the GitHub URL is a placeholder.

**Fix.** Point it at a real repository, or run the stages by hand - which is exactly what
was done here, and is recorded as limitation A3 in `docs/16` rather than papered over.

---

## 7. Ansible

### 7.1 `ERROR! the role 'common' was not found in /work/playbooks/roles:...`

**Cause.** ⚠ **The most misleading error in this project.** The roles were exactly where
they should be. The real cause was a `WARNING` printed *before* it:

```text
[WARNING]: Ansible is being run in a world writable directory (/ansible),
ignoring it as an ansible.cfg source.
```

The project directory is bind-mounted from Windows and arrives world-writable. Ansible
refuses to read `ansible.cfg` from such a directory - so `roles_path` and `inventory`
were never loaded - then continues and fails with an error naming a different problem
entirely.

**Fix.** Stage the mount into a private copy first
(`infra/ansible/entrypoint.controller.sh`):

```bash
cp -a /ansible/. /work/
chmod -R go-w /work
cd /work
```

**Lesson:** a warning that changes behaviour is an error. Do not go looking at your roles
until you have checked for warnings above the failure.

### 7.2 `Failed to find required executable "setup-timezone"`

**Cause.** The timezone module needs `setup-timezone`, which ships in `alpine-conf` -
and the role configured the timezone before installing the tool that configures it.

**Fix.** Add `alpine-conf` to the Alpine baseline packages **and** order the role so
packages are installed before anything is configured with them. Configure-then-install
cannot work on any platform that needs the tool.

### 7.3 The demo plays a `crfd:0.1.0` pull from Docker Hub

**Cause.** The demo overrides were placed in the inventory file as
`[crfd:vars] app_pull_image=false`. Variables in an inventory *file* sit **below**
`inventory/group_vars/all.yml`, so the production defaults won. A value that is silently
ignored is worse than a missing one, because it looks configured.

**Fix.** Demo values live in `inventory/demo-overrides.yml` and are applied as **extra
vars**, which outrank everything:

```bash
ansible-playbook -i inventory/hosts.demo.ini \
                 -e @inventory/demo-overrides.yml \
                 -e app_version=0.1.0 playbooks/site.yml
```

`hosts.demo.ini` now holds connection details only, with a comment explaining why the
tempting place is the wrong one.

### 7.4 The second run is not idempotent

```text
changed=2   (should be changed=0)
```

**Cause.** Something rewrites a file on every run. In this project it was the
provisioning banner, which embedded `ansible_date_time`, and the release-record task,
which had `recreate: true`.

**Fix.**

- the banner uses `force: false` (which is also semantically righter - it records when
  the node was *first* provisioned);
- the rollback record is written **only when the version changes**, which is what keeps
  `changed=0` *and* keeps the rollback point meaningful.

**Find it:**

```bash
ansible-playbook ... | grep -E 'changed'
```

### 7.5 `docker_image_info` does not find an image that is definitely present

**Cause.** The `name` filter matched differently from a list-and-compare; the image was
local and untagged for the search.

**Fix.** List all images and match `RepoTags` explicitly. If a lookup "cannot see" an
image, verify by hand before changing the play:

```bash
docker images --format '{{.Repository}}:{{.Tag}}'
```

### 7.6 Wrong hosts, or the play hangs on SSH

**Cause.** `hosts.example.ini` is a placeholder; the demo needs `hosts.demo.ini`.

**Fix.**

```bash
ansible -i inventory/hosts.demo.ini crfd -m ping      # always ping first
```

The demo node authenticates with a password (`sshpass`) because a throwaway `dind`
container needs a trivial credential; `hosts.example.ini` shows the key-based shape.

### 7.7 Rollback runs but the node will not roll forward again

**Cause.** A rollback that records only "go back to X" leaves no record of where you
came from.

**Fix.** `previous-release.txt` is written from the release being *left*, so recovery is
symmetric - after rolling back, the node can roll forward. This design came out of a
review question on PR #17. Verify:

```bash
cat /etc/crfd/previous-release.txt
cat /etc/crfd/release-history.log
```

---

## 8. Git

### 8.1 `fatal: pathspec 'X' did not match any file(s) known to git`

**Cause.** The file is untracked or genuinely absent. Adding a file inside an ignored
directory produces the same message.

**Fix.** `git status --short` and `git check-ignore -v <path>`. Note that a negation in
`.gitignore` only works if the *directory* is not excluded - which is why this repository
un-ignores `docs/evidence/**` as a whole before re-including its `.log` files. Without
that, the entire evidence trail silently disappears from a fresh clone.

### 8.2 A merge conflict

**Cause.** Two branches changed the same region. Normal, not an error.

**Fix.** The procedure is in `BRANCHING.md` §6 and demonstrated in `docs/07` §6:

1. reproduce on a branch, never directly on `develop`;
2. read both sides for **intent**, not text;
3. keep both intents where complementary, choose a side only when they contradict;
4. **re-run the full gate** - a resolution is code nobody has written;
5. commit the merge, keeping both parents.

```bash
grep -rn '<<<<<<<\|=======\|>>>>>>>' .    # a marker in YAML is not a syntax error
git log -1 --format='%h parents=%p'       # verify it is a real merge
```

Step 4 is the one people skip. Step 5 is the one that is impossible to fix afterwards.

### 8.3 `git push` rejected: `non-fast-forward`

**Cause.** The remote branch has commits you do not have - usually because the branch was
merged and deleted.

**Fix.** `git fetch --prune`, then rebase or start a fresh branch. **Do not
force-push a branch that has been reviewed** - a review that can be silently invalidated
is not a review (`BRANCHING.md` §5).

### 8.4 A `.sh` file arrives with CRLF after clone

**Cause.** No `.gitattributes` policy, or a local `core.autocrlf=true` overriding it.

**Fix.** This repository has `.gitattributes`; verify and renormalise:

```bash
git config core.autocrlf false
git add --renormalize .
```

---

## 9. Windows and PowerShell specifics

Only relevant if you are driving the project from PowerShell, which is how the Windows
side of this project was run.

### 9.1 `-D` arguments containing a second dot get mangled

**Symptom.** `-Dselenium.baseUrl=http://crfd-app:8080` is passed through wrong, or the
shell splits it.

**Fix.** Always quote the whole argument:

```powershell
.\mvnw.cmd -B -P selenium failsafe:integration-test "-Dselenium.baseUrl=http://crfd-app:8080"
```

### 9.2 A function returns more than you asked for

**Symptom.** A helper that should return a status code returns an array, so an `if`
test on it always takes the "failed" branch - reporting success as failure.

**Cause.** `Tee-Object` (and many cmdlets) write to the **output stream**, which becomes
part of the function's return value.

**Fix.** Pipe the display to `Out-Host` so it does not enter the pipeline:

```powershell
$output | Tee-Object -FilePath $log | Out-Host
```

This caused a real false-failure in `scripts/ansible-demo.ps1`: every *successful* play
was reported failed. It is worth knowing because the symptom - "all five steps failed" -
looks exactly like a broken playbook.

### 9.3 `Select-String` reports a bogus exit code 1

**Symptom.** A pipeline containing `Select-String` reports failure even after `BUILD
SUCCESS`.

**Cause.** `Select-String` sets the exit code when it finds nothing, and the pipeline
propagates it.

**Fix.** Check `$LASTEXITCODE` immediately after the *native* command, before any
pipeline, or capture the output first and search the string in memory.

### 9.4 Single-quoted `sh -c '...'` arguments get stripped

**Fix.** Use a double-quoted PowerShell string with single quotes inside:

```powershell
docker exec crfd-app sh -c "wget -qO- http://localhost:8080/api/v1/health"
```

Avoid `\"` inside a double-quoted PowerShell string; the parser rejects it.

### 9.5 `Get-Content -Raw` shows mojibake

**Cause.** PowerShell 5.1 decodes as CP1252 by default.

**Fix.** `Get-Content -Encoding UTF8`, and write with
`[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`
to avoid a BOM.

### 9.6 A generated script is blocked: "contains malicious content"

**Symptom.** `ScriptContainedMaliciousContent` from PowerShell/AMSI.

**Cause.** A long generated script that writes many files in a loop can trip an
antivirus heuristic.

**Fix.** Not a code problem. Split the generator, or do the work with a non-PowerShell
tool (`node`, `python`, `sh`). This happened once while building this report.

---

## 10. Diagnostic cheat sheet

```bash
# ---- the four layers --------------------------------------------------------
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker inspect --format '{{.State.Health.Status}}' crfd-app
docker logs --tail 100 crfd-app
curl -fsS http://localhost:18080/api/v1/health | jq .
curl -fsS http://localhost:18080/api/v1/health/slo | jq .

# ---- is it serving, or is it perfect? --------------------------------------
curl -i http://localhost:18080/api/v1/health       # 200 for UP *and* DEGRADED
curl -i http://localhost:18080/api/v1/health/slo   # 503 when BREACHED is correct

# ---- which revision is actually running? -----------------------------------
docker inspect crfd:1.0.0 --format '{{json .Config.Env}}' | tr ',' '\n' | grep BUILD_REVISION
docker inspect crfd-app --format '{{json .Mounts}}'       # look for a NAMED volume

# ---- the pipeline's own network view --------------------------------------
docker network inspect crfd-ci-net --format '{{range .Containers}}{{.Name}} {{end}}'

# ---- the gate ---------------------------------------------------------------
SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub \
./mvnw -B -P selenium verify -Dselenium.baseUrl=http://crfd-app:8080
ls target/failsafe-reports/         # screenshots land beside the reports

# ---- provisioning -----------------------------------------------------------
ansible -i inventory/hosts.demo.ini crfd -m ping
ansible-playbook -i inventory/hosts.demo.ini -e @inventory/demo-overrides.yml playbooks/site.yml
cat /etc/crfd/previous-release.txt /etc/crfd/release-history.log

# ---- the repository ---------------------------------------------------------
git log --oneline --graph --all | head -30
git log --first-parent --oneline develop
git status --short && git check-ignore -v <path>
```

**The one habit worth keeping:** when the layers disagree, believe the lowest one that
can answer the question. A `curl` that returns 200 against an `unhealthy` container means
the health check is asking the wrong question - which is precisely the failure this
project made twice, and the reason it now has two endpoints.
