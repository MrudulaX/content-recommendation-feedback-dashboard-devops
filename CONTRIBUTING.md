# Contributing to CRFD

Thanks for improving the Content Recommendation Feedback Dashboard. This document is
the short version of `BRANCHING.md`, plus the local developer workflow.

## 1. Get set up

```bash
git clone <your-fork-or-repo-url>
cd content-recommendation-feedback-dashboard
./mvnw clean verify          # Windows: .\mvnw.cmd clean verify
./mvnw spring-boot:run       # http://localhost:8080
```

Prerequisites: **JDK 21**, **Maven 3.9+** (wrapper included), Docker (optional).
See `docs/18-local-runbook.md` for the full environment matrix.

## 2. Pick up work

1. Find or create an issue (`feature_request` / `bug_report` form).
2. Branch: `git checkout -b feature/CRFD-123-my-change develop`
3. Code, with tests. New behaviour ⇒ new test.
4. Run the local quality gate:
   ```bash
   ./mvnw -B clean verify
   ./mvnw -P selenium verify -Dselenium.baseUrl=http://localhost:8080
   ```
5. Commit using Conventional Commits (see `BRANCHING.md`).
6. `git push -u origin HEAD`, open a PR against `develop`.

## 3. Coding standards

- Java 21, 4-space indent, no wildcard imports.
- Controllers stay thin: validate → delegate to a service → map to a DTO record.
- Never return JPA entities from a controller; use the `web/dto` records.
- All new REST endpoints must appear in `docs/05-api-catalogue.md`
  and in the OpenAPI UI (`/swagger-ui.html`).
- Front-end lives in `src/main/resources/static/` (no bundler, plain ES2020 modules).
- Configuration goes through `application*.yml` / environment variables — no hard-coded
  hostnames, ports or credentials.

## 4. Definition of Done

The authoritative checklist is in `docs/02-agile-plan.md`.
In short: code + tests + docs + green pipeline + reviewed PR + merged + issue closed.

## 5. Code of conduct

Be respectful, assume good intent, review the code and not the person. Disagreements are
resolved with evidence (a test, a measurement, a reference), not volume.
