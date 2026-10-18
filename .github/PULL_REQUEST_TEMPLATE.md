# Pull request

## What this changes

<!-- One or two sentences. Link the story: Closes #<issue> -->

Closes #

## Type of change

- [ ] `feat` - new MVP capability
- [ ] `fix` - defect repair
- [ ] `test` - tests only
- [ ] `docs` - documentation only
- [ ] `build` / `ci` - pipeline, packaging, image
- [ ] `refactor` - behaviour-preserving change
- [ ] `chore` - housekeeping

## How it was verified

<!-- Be specific: the exact command you ran and what you saw. -->

| Check | Command | Result |
| --- | --- | --- |
| Unit + integration tests | `mvn -B test` | |
| Selenium journeys | `mvn -B -P selenium verify` | |
| Container smoke test | `scripts/smoke-test.sh` | |
| Manual check | | |

## Evidence

<!--
Attach or link what a reviewer needs to trust this without re-running it:
screenshots, the Surefire summary, the Selenium screenshot set, the pipeline URL.
-->

## Reviewer checklist

- [ ] The change does what the linked issue asked for, and nothing more
- [ ] Acceptance criteria are covered by an automated test
- [ ] No secrets, credentials or absolute local paths committed
- [ ] Error cases return the uniform error body, not a raw stack trace
- [ ] `CHANGELOG.md` updated
- [ ] Docs updated when behaviour, configuration or the API changed

## Deployment notes

<!--
Anything the person deploying needs to know: new environment variables, a
migration, a config change, whether an image rebuild is required.
-->
- [ ] No deployment action required
- [ ] Requires an image rebuild / redeploy
- [ ] Requires a configuration or environment change (list below)

## Screenshots (UI changes only)

| Before | After |
| --- | --- |
|  |  |
