---
name: User story
about: A unit of MVP scope, sized for one sprint
title: "[CRFD-###] "
labels: ["user-story"]
assignees: ""
---

## User story

**As a** <!-- role, e.g. content platform engineer -->
**I want** <!-- capability -->
**so that** <!-- the outcome that makes it worth building -->

## Acceptance criteria

<!--
Written as checkable statements. These become the Selenium journeys and the
integration-test assertions, so they must be observable from outside the system.
-->

- [ ] Given ..., when ..., then ...
- [ ] Given ..., when ..., then ...

## Definition of Ready

- [ ] Acceptance criteria agreed and observable
- [ ] Traces to a frozen MVP capability (CRFD-101..106)
- [ ] Fits inside one sprint
- [ ] No unresolved dependency on another story in the same sprint

## Definition of Done

- [ ] Code reviewed and merged to `develop` through a PR
- [ ] Unit / integration tests cover the acceptance criteria and pass in CI
- [ ] Selenium journey updated if the change touches a critical user journey
- [ ] API change reflected in OpenAPI and in `docs/05-api-catalogue.md`
- [ ] `CHANGELOG.md` updated
- [ ] Documentation updated

## Traceability

| Field | Value |
| --- | --- |
| MVP capability | CRFD-10x |
| Sprint | Sprint n |
| Estimate | n points |
| Depends on | |
| Evidence produced | |

## Tasks

- [ ] <!-- break the work down -->
