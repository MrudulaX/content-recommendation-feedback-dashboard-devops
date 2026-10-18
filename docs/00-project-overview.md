# 00 — Project overview

## What this project is

A DevOps project delivered around one working product: the **Automated Content
Recommendation Feedback Dashboard** (CRFD). Recommendation feedback events are
ingested, processed, stored, queried, summarised, drilled into and alerted on
through a web dashboard — and the whole thing is built, tested, packaged,
containerised, deployed and provisioned by an automated toolchain.

Two things are being assessed at once, and this pack keeps them clearly apart:

1. **The product** — does the MVP actually do what the frozen scope says?
2. **The delivery mechanism** — is there a real, working Git → CI → test →
   container → provision → deploy → recover chain around it?

Everything in this repository is either the product, the toolchain, the evidence
that both work, or the writing that explains them.

## The MVP in one paragraph

An operator opens the dashboard and immediately sees whether the recommendation
pipeline is healthy: how many events arrived, how many were processed, the p95
serving latency, how many exceptions are open, and a deep-health status. They can
search events by free text and facets, export the current filter to CSV, open any
event to see its full status-transition timeline and the exceptions it raised,
manually move an event's status, submit a new feedback event, and work an
exception queue by acknowledging and resolving alerts. Six capabilities, frozen
at the start of the project, and nothing else.

## Deliverables and where they live

| Deliverable the brief asks for | Where it is |
| --- | --- |
| Working MVP web application | `src/main/java`, `src/main/resources/static` |
| Frozen MVP scope, Agile backlog, sprint plan, Definition of Done | `docs/02-agile-plan.md` |
| DevOps lifecycle diagram | `docs/02-agile-plan.md`, `docs/03-srs-and-architecture.md` |
| Stack / build tool / database / deployment target | `docs/03-srs-and-architecture.md`, `README.md` |
| Git + GitHub: README, `.gitignore`, structure, templates, branching, commits, PRs, conflict, tags | `README.md`, `BRANCHING.md`, `CONTRIBUTING.md`, `.github/`, `docs/06`, `docs/07` |
| Jenkins install, configuration, GitHub job, build on commit, archived artifact | `infra/jenkins/`, `Jenkinsfile`, `docs/08` |
| Jenkinsfile: checkout / build / package / deploy, parameterised environments | `Jenkinsfile`, `docs/09` |
| Selenium: 3–5 journeys, run via Maven, screenshots on failure, in Jenkins, blocks deploy | `src/test/java/.../selenium/`, `docs/10`, `docs/11` |
| Deliberate defect, fix, rerun | `docs/11-continuous-testing-and-defect-fix.md` |
| Docker: Dockerfile, build/tag/run, port mapping, logs, stop/restart/remove | `Dockerfile`, `scripts/docker-lifecycle.*`, `docs/12` |
| Jenkins builds a versioned image, publishes it, deploys a fresh container | `Jenkinsfile`, `infra/jenkins/`, `docs/13` |
| Ansible: clean node, idempotency, health check, rollback/recovery | `infra/ansible/`, `scripts/ansible-demo.*`, `docs/14` |
| End-to-end workflow, report, troubleshooting, limitations, future work, viva | `docs/15`, `docs/16`, `docs/17`, `docs/18` |
| Evidence (logs, screenshots, outputs) | `docs/evidence/` |

> **A note on the brief.** The original brief lists *"MVP Completion and Git
> Collaboration"* and *"Jenkins–Docker Continuous Deployment"* twice. The duplicate
> entries have been merged into one deliverable each (`docs/07` and `docs/13`)
> rather than delivered twice. This is recorded here so the mapping stays explicit.

## How to read this pack

If you have ten minutes, read `README.md` and then `docs/15-final-end-to-end-and-viva.md`.

If you are reviewing the engineering, read in order:

```
01 problem & scope   → what and why, and what was deliberately excluded
02 agile plan        → the backlog, sprints, DoD, DevOps lifecycle
03 SRS & architecture→ requirements and the design that satisfies them
04 data model        → four tables and why
05 API catalogue     → the contract, endpoint by endpoint
```

If you are reconstructing the delivery process, read:

```
06 git & github      → branching, commits, PRs, review
07 MVP & conflict    → MVP completion, the merge conflict, the tags
08 jenkins ci job    → controller setup as code, the CI job
09 pipeline          → stage by stage, and the Tomcat/Nginx deployment
10 selenium plan     → the journeys and locator strategy
11 continuous testing→ what the suite caught, and the defect fix
12 docker lifecycle  → image design and the container lifecycle
13 jenkins → docker  → the continuous deployment chain
14 ansible           → provisioning, idempotency, rollback
```

If something will not start, read `17-troubleshooting-guide.md`, then `18-local-runbook.md`.

## Verification status at the time of writing

| What | Status |
| --- | --- |
| Unit + integration tests | **31 passing** (`mvn -B test`) |
| Selenium journeys | **5 passing** against the containerised app with containerised Chrome |
| Docker image | built, 401 MB, health-checked, proved to be JRE-only |
| Container lifecycle | run / logs / stop / start / restart / remove exercised |
| Deployment smoke test | **11/11 passing** against the container |
| Jenkins pipeline | defined in `Jenkinsfile`; controller provisioned by `infra/jenkins/` |
| Ansible provisioning | playbooks + controller + disposable node image; see `docs/14` |

Every figure above is reproducible from the commands in `README.md` and
`docs/18-local-runbook.md`; captured output is in `docs/evidence/`.
