# Docker, dataset and database - guided tour

Where to point when someone asks "show me what you built in Docker", "what is the
dataset" and "where is the database".

Short answers:

- **Docker** - the image is defined by `Dockerfile`, the stack by
  `docker-compose.yml` (app + Nginx + named volume + network); Jenkins rebuilds and
  redeploys both at <http://localhost:8085>.
- **Dataset** - there is no external CSV. The application generates a synthetic
  48-hour history of 180 feedback events on first start (`DataSeeder`), then records
  every real event, alert and piece of feedback that arrives through the API.
- **Database** - one embedded H2 file database, `crfd.mv.db`, held in the named
  Docker volume `crfd-data`, with four tables: `recommendation_event`, `alert`,
  `event_audit`, `recommendation_feedback`. It can be queried in a browser at
  `/h2-console`.

## 1. What to open in the repository

| To show | Open |
| --- | --- |
| The image definition (multi-stage, non-root, healthcheck) | `Dockerfile` |
| The application stack: app + Nginx + volume + network | `docker-compose.yml` |
| The MySQL variant of the same stack | `docker-compose.mysql.yml` |
| Nginx reverse proxy configuration | `infra/nginx/nginx.conf` |
| Jenkins controller as code + pipeline | `infra/jenkins/docker-compose.jenkins.yml`, `infra/jenkins/jenkins.yaml`, `infra/jenkins/plugins.txt`, `Jenkinsfile` |
| Selenium browser grid | `infra/selenium/docker-compose.selenium.yml` |
| Ansible provisioning of a clean node | `infra/ansible/` (playbooks, roles, inventories) |
| Why each object exists | `docs/12-docker-lifecycle.md` |
| The tables and their relationships | `docs/04-data-model.md` |
| The endpoints behind the dashboard | `docs/05-api-catalogue.md` |

## 2. Docker objects to show live

```powershell
# What is running, from which image, on which port
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"

# The image Jenkins built, with its provenance labels
docker images | Select-String "crfd"
docker image inspect crfd:v3.3.5-11 --format "{{json .Config.Labels}}"

# The container's environment proves the Jenkins parameters reached it
docker inspect crfd-app --format "{{range .Config.Env}}{{println .}}{{end}}" | Select-String "CRFD_|SERVER_PORT|DB_URL"

# The persistent volume and where it lives on the host
docker volume ls
docker volume inspect crfd-data --format "{{.Name}} -> {{.Mountpoint}}"
docker inspect crfd-app --format "{{range .Mounts}}{{.Type}} {{.Name}} -> {{.Destination}}{{println}}{{end}}"

# The private registry the pipeline can push to
docker ps --format "{{.Names}} {{.Ports}}" | Select-String "crfd-registry"

# Logs of the running application
docker logs --tail 40 crfd-app
```

What each answer means:

- `crfd-app` runs the image the **pipeline** built (`crfd:v3.3.5-11`), not one built
  by hand. The tag encodes the Maven version and the Jenkins build number.
- `CRFD_ENVIRONMENT=staging` is the `ENVIRONMENT` build parameter, proving the
  deployment came from Jenkins.
- `crfd-data` is a **named volume**, so `docker rm crfd-app` (which the deploy stage
  does on every build) does not lose data.

## 3. The dataset

There are three sources, all landing in the same database:

| Source | Volume | How it appears |
| --- | --- | --- |
| Startup seed | 180 events over the previous 48 hours (configurable) | `DataSeeder` calls `TrafficSimulator.seedHistory(...)` on first start only - it skips when the table already has rows, so restarts do not duplicate data |
| Traffic simulator | ongoing, small batches | background generator producing impressions, clicks, dismissals, ratings so the 24-hour trend line moves |
| Real traffic | whatever arrives | the dashboard's "Add event" form, the feedback form, `POST /api/v1/events`, the Jenkins smoke test, and the five Selenium journeys |

Event fields (the payload contract): `userId`, `itemId`, `itemTitle`, `eventType`,
`source`, `surface`, `score`, optional `latencyMs`. The enumerations are in
`docs/04-data-model.md`; the seeded rows are backdated so the dashboard shows a
48-hour history immediately instead of an empty chart.

Configuration, all overridable by environment variable:

```yaml
crfd:
  simulation:
    enabled:        ${CRFD_SIMULATION_ENABLED:true}
    seed-on-startup: ${CRFD_SEED_ON_STARTUP:true}
    seed-event-count: ${CRFD_SEED_EVENT_COUNT:180}
    seed-hours-back: ${CRFD_SEED_HOURS_BACK:48}
```

Reset the dataset (drops the volume, next start re-seeds):

```powershell
docker rm -f crfd-app
docker volume rm crfd-data
# then let the pipeline redeploy, or: docker compose up -d --build
```

Production profile disables both the seed and the simulator
(`application-prod.yml`: `simulation.enabled=false`, `seed-on-startup=false`), so a
real deployment never invents traffic.

## 4. The database

Embedded H2 in file mode - no separate database server, no credentials to manage,
and the file is inside the volume:

```
container path : /app/crfd-data/crfd.mv.db
host path      : /var/lib/docker/volumes/crfd-data/_data/crfd.mv.db
JDBC URL       : jdbc:h2:file:/app/crfd-data/crfd;DB_CLOSE_ON_EXIT=FALSE
user / password: sa / (empty)
schema         : created and migrated by Hibernate (ddl-auto=update)
```

Show the file itself:

```powershell
docker exec crfd-app ls -lh /app/crfd-data
docker exec crfd-app sh -c "du -h /app/crfd-data/crfd.mv.db"
```

Show the tables in a browser (this is the part examiners like):

1. Open <http://localhost:18080/h2-console> (or `:8080` if you are running the
   compose stack).
2. JDBC URL: `jdbc:h2:file:/app/crfd-data/crfd` - user `sa` - password empty.
3. Connect, then run:

```sql
SELECT COUNT(*) FROM recommendation_event;
SELECT status, COUNT(*) FROM recommendation_event GROUP BY status;
SELECT source, COUNT(*) FROM recommendation_event GROUP BY source;
SELECT * FROM recommendation_event ORDER BY id DESC LIMIT 5;

SELECT code, severity, status, COUNT(*) FROM alert GROUP BY code, severity, status;
SELECT * FROM alert WHERE status = 'OPEN' ORDER BY id DESC LIMIT 5;

SELECT * FROM event_audit ORDER BY id DESC LIMIT 10;
SELECT * FROM recommendation_feedback ORDER BY id DESC LIMIT 5;
```

Or query it from a terminal, without the browser. H2 lives inside the Boot fat jar
(`BOOT-INF/lib`), so copy the database file out and run the H2 tool from the local
Maven repository - both are on the host already:

```powershell
docker cp crfd-app:/app/crfd-data/crfd.mv.db "$env:TEMP\crfd-copy.mv.db"
$h2 = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter "h2-*.jar" |
      Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1
java -cp $h2.FullName org.h2.tools.Shell `
     -url "jdbc:h2:file:$env:TEMP\crfd-copy" -user sa `
     -sql "SELECT status, COUNT(*) FROM recommendation_event GROUP BY status"
```

A recorded run of the queries above (29 October 2026, data behind build #11):

```
SELECT status, COUNT(*) AS n FROM recommendation_event GROUP BY status;
--> FAILED 20 | PROCESSED 303 | PROCESSING 5 | QUARANTINED 6 | RECEIVED 4
SELECT source, COUNT(*) AS n FROM recommendation_event GROUP BY source;
--> HYBRID 95 | CONTENT_BASED 65 | COLLABORATIVE_FILTER 64 | TRENDING 60 | EDITORIAL 54
SELECT code, status, COUNT(*) AS n FROM alert GROUP BY code, status;
--> LOW_CONFIDENCE OPEN 57 | ACKNOWLEDGED 14 | RESOLVED 5
    SCHEMA_MISMATCH OPEN 17 | ACKNOWLEDGED 4  | RESOLVED 5
    HIGH_LATENCY   OPEN  9 | ACKNOWLEDGED 1
    RATING_DROP    OPEN  2
SELECT COUNT(*) FROM recommendation_feedback;  --> 25
SELECT COUNT(*) FROM event_audit;              --> 825
```

The same numbers are available over HTTP, which is evidence you can paste into a
report without a browser:

```powershell
(Invoke-RestMethod "http://localhost:18080/api/v1/events?size=1").totalElements
(Invoke-RestMethod "http://localhost:18080/api/v1/alerts?size=1").totalElements
(Invoke-RestMethod "http://localhost:18080/api/v1/feedback?size=1").totalElements
Invoke-RestMethod "http://localhost:18080/api/v1/metrics/summary"
```

### Tables

| Table | Rows hold | Key columns |
| --- | --- | --- |
| `recommendation_event` | every feedback event and its processing outcome | `event_id`, `user_id`, `item_id`, `event_type`, `source`, `surface`, `score`, `status`, `latency_ms`, `message` |
| `alert` | exceptions raised by the rules (low confidence, high latency, ...) | `alert_ref`, `event_ref`, `code`, `severity`, `status`, `owner`, `resolution_note` |
| `event_audit` | status transitions for the drill-down timeline | `event_ref`, `from_status`, `to_status`, `note`, `actor` |
| `recommendation_feedback` | explicit user feedback (CRFD-106) | `feedback_ref`, `event_ref`, `user_id`, `item_id`, `rating`, `reason` |

`alert.event_ref` and `event_audit.event_ref` point at
`recommendation_event.event_id`; `recommendation_feedback.event_ref` does too. That
join is what the dashboard's status drill-down renders.

### MySQL variant

`docker-compose.mysql.yml` keeps the same schema on MySQL, so the data model is not
tied to H2:

```powershell
docker compose -f docker-compose.mysql.yml up -d
# DB_URL=jdbc:mysql://crfd-mysql:3306/crfd, JPA_DDL_AUTO=update
```

## 5. Ten-minute demonstration order

1. `docker ps` - Jenkins, the app, Selenium, the registry, all containers.
2. `docker images | Select-String crfd` - the tag Jenkins produced for this build.
3. `docker inspect crfd-app` - image tag + `CRFD_ENVIRONMENT=staging` prove the
   pipeline deployed it.
4. `docker volume inspect crfd-data` - data is on a volume, not inside the container.
5. <http://localhost:18080> - the dashboard, populated with 48 hours of data.
6. <http://localhost:18080/h2-console> - connect and run
   `SELECT status, COUNT(*) FROM recommendation_event GROUP BY status;`.
7. Pick an event in the dashboard, open its drill-down - the rows you see are the
   `event_audit` and `alert` records you just queried.
8. <http://localhost:8085/job/crfd-dashboard-pipeline/11/> - the green build that
   produced all of it, with archived jar, JUnit and coverage reports.
9. `docker rm -f crfd-app` then rebuild in Jenkins - the data survives, because it
   lives in the volume. (This is the point of the volume, and a good thing to show.)
10. `docs/evidence/jenkins-pipeline-run.md` - the recorded evidence of the run.

## 6. Caveats worth saying out loud

- The H2 console is enabled by default for the demo. `application-prod.yml` turns it
  off, together with seeding and the simulator.
- H2 in file mode is single-writer: one application instance owns the file. That is
  fine for an MVP and is listed in `docs/16-limitations-and-future-enhancements.md`
  as the reason MySQL is provided as the scale-out path.
- Seeding runs only when the `recommendation_event` table is empty, so the row count
  you see is seed + everything the pipeline, smoke tests and Selenium journeys have
  submitted since the volume was created.
- `docker compose down -v` removes the volume, and with it the whole dataset.
