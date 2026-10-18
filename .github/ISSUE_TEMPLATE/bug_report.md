---
name: Bug report
about: Something in the dashboard, API or pipeline behaves incorrectly
title: "[BUG] "
labels: ["bug", "needs-triage"]
assignees: ""
---

## What happened

<!-- A clear, specific description of the defect. -->

## What was expected

<!-- What should have happened instead. -->

## Steps to reproduce

1.
2.
3.

## Reproducibility

- [ ] Every time
- [ ] Intermittently (how often? __%)
- [ ] Only seen once

## Environment

| Field | Value |
| --- | --- |
| App version / tag | <!-- e.g. v1.0.0, or the commit sha --> |
| Environment | dev / staging / production / docker / local |
| Deployment | local JAR / docker / docker compose / Jenkins pipeline |
| Browser (if UI) | |
| OS | |

## Evidence

<!--
Paste the relevant log lines, the failing request/response, or a screenshot.
For Selenium failures the harness writes target/selenium-screenshots/<test>.png
automatically - attach it rather than describing the screen.
-->

## Impact

- [ ] Blocks the release
- [ ] Degrades the dashboard but a workaround exists
- [ ] Cosmetic

## Suspected cause / notes

<!-- Optional. If you already found the offending commit or line, link it. -->
