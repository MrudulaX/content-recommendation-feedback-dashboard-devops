# Branching Policy & Collaboration Rules

> Applies to the **Content Recommendation Feedback Dashboard (CRFD)** repository.
> Model: **GitHub Flow with a protected integration branch** (`develop`) and a
> release branch (`main`).

## 1. Long-lived branches

| Branch  | Purpose | Protection |
|---------|---------|------------|
| `main`  | Always releasable. Only receives merges from `release/*` or `hotfix/*`. Every commit is tagged. | Protected: no direct push, PR + review required, 1 approval, linear history, status checks must pass. |
| `develop` | Integration branch for the current 15-week MVP. Feature branches merge here. | Protected: no direct push, PR + review required, CI (`mvn verify`) must pass. |

## 2. Short-lived (working) branches

Naming convention - `type/CRFD-<issue-number>-<short-kebab-description>`:

| Type | When to use | Example |
|------|-------------|---------|
| `feature/` | New user-facing capability from the backlog | `feature/CRFD-101-event-ingestion-api` |
| `bugfix/` | Non-production defect | `bugfix/CRFD-207-alert-filter-pagination` |
| `hotfix/` | Production defect, branched from `main` | `hotfix/CRFD-311-null-item-id-500` |
| `release/` | Release stabilisation, branched from `develop` | `release/1.0.0` |
| `chore/` | Build, CI, dependency, docs-only work | `chore/CRFD-402-bump-spring-boot` |
| `docs/` | Documentation-only work | `docs/CRFD-410-troubleshooting-guide` |

Rules:
- Branches are created **from an up-to-date `develop`** (or `main` for hotfixes).
- One branch = one issue = one logical change. Keep branches under ~5 days of life.
- Never commit directly to `main` or `develop`.
- Always `git pull --rebase origin develop` before opening a PR.

## 3. Commit message convention (Conventional Commits)

```
<type>(<scope>): <short imperative summary>

<body: what and why, wrap at 72 chars>

Refs: #<issue-number>
```

Allowed types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

Examples used in this project:

```
feat(events): add POST /api/v1/events ingestion endpoint
fix(alerts): stop duplicate alerts on replayed event ids
ci(jenkins): gate deploy stage on Selenium quality report
```

## 4. Pull request rules

1. Open the PR against `develop` using the repository PR template.
2. Link the issue (`Closes #12`).
3. Minimum **1 approving review**; the author may not approve their own PR.
4. All of the following must be green before merge:
   - `mvn -B clean verify` (unit + integration tests)
   - Selenium suite `-P selenium`
   - No new *critical* Sonar/static-analysis findings (where configured)
5. Merge strategy: **Squash and merge** for feature branches, **merge commit** for
   `release/*` so the merge is visible in history.
6. Delete the source branch after merge.

## 5. Versioning and tags

Semantic versioning `MAJOR.MINOR.PATCH`, annotated tags only:

| Tag | Meaning |
|-----|---------|
| `v0.1.0-mvp` | Frozen 15-week MVP scope baseline |
| `v1.0.0` | First release-ready, containerised, CI/CD-verified build |

```bash
git tag -a v1.0.0 -m "Release 1.0.0 - MVP complete" && git push origin v1.0.0
```

## 6. Conflict handling

Conflicts are resolved **on the feature branch**, never on the protected branch:

```bash
git checkout feature/CRFD-105-alert-exception-view
git fetch origin
git merge origin/develop          # conflict surfaces here
# fix files, then:
git add <files>
git commit -m "chore: resolve merge conflict with develop on alert filter"
git push
```

The resolution commit must keep **both** intents where they are complementary and
must be covered by a test. Resolution evidence is stored under `docs/evidence/`.

## 7. Issue templates

Issue forms live in `.github/ISSUE_TEMPLATE/` (`bug_report.md`,
`user_story.md`). Every branch maps to exactly one issue.
