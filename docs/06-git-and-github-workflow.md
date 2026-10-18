# 06 — Git and GitHub workflow

This chapter is the process the whole project was delivered under: branch policy,
commit convention, the review path, the merge strategy, and the release baseline.
The reader's starting point is the repository itself - everything stated here can be
checked with a `git` command, and the commands are given.

---

## 1. The artefacts that define the process

| File | What it fixes |
| --- | --- |
| `BRANCHING.md` | Branch naming, commit format, PR rules, tag policy, the conflict procedure |
| `CONTRIBUTING.md` | How to build, test and submit a change |
| `.github/ISSUE_TEMPLATE/bug_report.md` | The fields a defect must arrive with |
| `.github/ISSUE_TEMPLATE/user_story.md` | The fields a story must arrive with |
| `.github/PULL_REQUEST_TEMPLATE.md` | The reviewer's checklist |
| `.gitattributes` | Line endings: LF in the repository for everything a Linux agent executes |

`.gitattributes` earns its place. The pipeline runs on a Linux agent (`Jenkinsfile`
uses `sh`, `bash`, `curl`, `sha256sum`) and the project is developed on Windows. A
Windows checkout that rewrites `scripts/wait-for-health.sh` with CRLF produces:

```text
bash: ./scripts/wait-for-health.sh: /bin/bash^M: bad interpreter
```

which reads as a missing interpreter and is actually a line ending. The policy is
`* text=auto eol=lf` with the shell and script types pinned explicitly, so the same
bytes are checked out on every platform:

```bash
$ git check-attr text eol -- scripts/wait-for-health.sh
scripts/wait-for-health.sh: text: set
scripts/wait-for-health.sh: eol: lf
```

---

## 2. Branching model

A two-branch model with short-lived topic branches - the shape a small team
actually uses, rather than the full GitFlow apparatus.

```text
main      ●────────────────────────────────●  v1.0.0      (releases only)
           \                              /
develop     ●──●──●──●──●──●──●──●──●──●──●               (integration)
             \    \      \      \
              \    feature/CRFD-101    bugfix/CRFD-117
               feature/CRFD-110          feature/CRFD-120
```

| Branch | Branches from | Merges into | Lives for | Purpose |
| --- | --- | --- | --- | --- |
| `main` | — | — | forever | Released, tagged states only |
| `develop` | `main` | `main` via a release branch | forever | Integration; always green |
| `feature/CRFD-nnn-slug` | `develop` | `develop` | one story | A new capability |
| `bugfix/CRFD-nnn-slug` | `develop` | `develop` | one defect | A defect repair |
| `release/vX.Y.Z` | `develop` | `main` **and** `develop` | one release | Stabilisation and release commit |
| `hotfix/vX.Y.Z` | `main` | `main` **and** `develop` | one incident | A fix for a released version |

**Rules, enforced by review rather than by tooling** (there is no server to enforce
them, which is stated rather than glossed over):

- every branch maps to exactly one backlog item, and the item number is in the name;
- `main` is only ever written by a release or hotfix merge, so its history *is* the
  release history;
- a topic branch is deleted after merge - the merge commit is the record;
- a branch that has been reviewed is not force-pushed; further work goes on a new
  branch, because a review that can be silently invalidated is not a review.

**The branches that were actually used** (`git branch --list`):

```text
main
develop
release/v1.0.0
feature/CRFD-110-repository-skeleton
feature/CRFD-111-maven-build-and-runtime-shell
feature/CRFD-101-event-ingestion-api
feature/CRFD-105-alert-exception-queue
feature/CRFD-103-summary-indicators
feature/CRFD-106-explicit-feedback
feature/CRFD-107-dashboard-ui
bugfix/CRFD-117-event-id-inconsistency
bugfix/CRFD-121-client-errors-return-500
bugfix/CRFD-105-readiness-vs-slo-gate
feature/CRFD-130-test-suite
feature/CRFD-112-docker-image
feature/CRFD-113-docker-and-smoke-scripts
feature/CRFD-116-selenium-browser-harness
feature/CRFD-114-jenkins-controller-as-code
feature/CRFD-115-jenkins-pipeline
feature/CRFD-120-ansible-provisioning
feature/CRFD-103-threshold-documentation
feature/CRFD-105-alert-threshold-documentation
```

---

## 3. Commit convention

[Conventional Commits](https://www.conventionalcommits.org/), with the backlog item
as the scope:

```text
<type>(<CRFD-id>): <imperative subject, no full stop, <= 72 chars>

<body: why, not what - the what is in the diff>

Refs CRFD-101 CRFD-101b
```

| Type | Used for | Count in this history |
| --- | --- | --- |
| `feat` | A new capability | 11 |
| `fix` | A defect repair | 3 |
| `docs` | Documentation only | 4 |
| `test` | Tests only | 2 |
| `ci` | Pipeline and controller | 2 |
| `chore` | Housekeeping, releases | 2 |
| `build` | Packaging and dependencies | 1 |
| **Total non-merge commits** | | **25** |

Two conventions that do real work:

**The scope is the backlog item, not the file or the layer.** `fix(CRFD-121)`
answers "which requirement did this change serve?"; `fix(web)` would answer "which
directory did it touch?", which `git diff --stat` already says.

**The body explains *why*.** The two defect commits are the clearest examples. They
do not describe the diff - a reviewer can read that - they record the reasoning that
isn't in it: that the write path was the one place that disagreed with the rest of
the system about what an event's identifier looks like, and that a 500 in response to
a client mistake is also a false alert. Six months later, that paragraph is the only
thing that explains why the code is the way it is.

The subject line is checked by eye against two rules: imperative mood ("return 404",
not "returned 404"), and no trailing full stop.

---

## 4. The workflow, as run

```text
1  pull develop            git checkout develop && git pull
2  branch                  git checkout -b feature/CRFD-101-event-ingestion-api
3  develop, build, test    mvn -B clean test          (green before anything else)
4  commit in slices        one logical change per commit, Conventional Commit message
5  re-verify               mvn -B clean package       (the full gate, not just the test)
6  push                    git push -u origin feature/CRFD-101-event-ingestion-api
7  pull request            template filled in, including the verification table
8  review                  checklist, comments; changes requested or approved
9  merge --no-ff           gives a merge commit naming the PR and the branch
10 delete the branch, delete the remote branch
```

Steps 2-5 were run incrementally on each branch rather than once at the end; the
build and test gates in `.github/workflows/ci.yml` and the `Jenkinsfile` are the
same commands step 5 runs.

---

## 5. Reviews and pull requests

Every branch went through a review, and each review is recorded with the template
filled in: **`docs/pull-requests/README.md`**, one section per PR, indexed by branch
and merge commit.

The reviews are not formalities, and that is the point. Of the 19:

- **7 asked for a change that was then made.** The most consequential:
  - PR #3 - bounded paging and a capped CSV export, after a reviewer noticed a
    caller could ask for 500 000 rows;
  - PR #10 - the container health check, Nginx and the Ansible gate were all
    re-pointed at `/health` so that a business metric stops controlling availability;
  - PR #12 - the `VOLUME` / anonymous-volume interaction, which made
    replace-the-container a silent data wipe;
  - PR #14 - re-locate instead of wait longer, after a reviewer pointed out that
    making a flaky test wait is not the same as making it stop flaking.
- **4 produced a decision that was argued and recorded** rather than an immediate code
  change (feedback not being idempotent, PR #6; SQL percentiles, PR #5; alert
  suppression, PR #4; `latest` built but never deployed, PR #12).
- **The remainder were approved with the verification evidence checked.**

The independent review that found the *most* was on PR #8, the `EVT-` defect: the
reviewer's question was "shouldn't the unit tests have caught this?" and the answer
- no, because they asserted the stored event could be found and it could, by the
UUID - is what turned the Selenium gate from a nice-to-have into the project's
central argument. `docs/11` is built on it.

### About the remote

**This repository has no GitHub remote.** The environment used to build it has no
GitHub account and no `gh`, so:

- the "remote" is a local bare repository, `crfd-origin.git`, created as a stand-in
  and used to verify that pushes, branch tracking and tag pushes work;
- pull requests are recorded in `docs/pull-requests/README.md` rather than opened;
- the merge commits are real `git merge --no-ff` commits whose messages name the PR
  number and the branch, so `git log --merges` reproduces the review list.

This is a substitution, and it is labelled as one in the PR record and in the
limitations chapter. What it is *not* is a claim that a GitHub feature was used when
it was not.

---

## 6. Merge strategy

**`git merge --no-ff`, deliberately.** Three strategies were available and the
choice matters more than it looks:

| Strategy | What it loses |
| --- | --- |
| Fast-forward | The branch disappears from the graph; a group of commits arrives on `develop` with nothing marking it as a reviewed unit |
| Squash | The intermediate commits disappear, so a bisect lands on one enormous commit and the review cannot be reconstructed from the history |
| **`--no-ff`** | Nothing except graph tidiness |

With `--no-ff`, `git log --first-parent develop` reads as the list of reviewed units,
which is exactly what an audit wants:

```bash
$ git log --first-parent --oneline develop
e054ea2 Merge pull request #19 from crfd/feature/CRFD-105-alert-threshold-documentation
e24ece6 Merge pull request #18 from crfd/feature/CRFD-103-threshold-documentation
91dc232 Merge pull request #17 from crfd/feature/CRFD-120-ansible-provisioning
cc10657 Merge pull request #16 from crfd/feature/CRFD-115-jenkins-pipeline
...
dfea120 Merge pull request #1 from crfd/feature/CRFD-110-repository-skeleton
```

and `git log --no-merges` gives the working history *inside* those units. Both views
are available, which is the property a fast-forward or squash history destroys.

---

## 7. Merge conflicts

Full account in `docs/07`; the policy from `BRANCHING.md` in one paragraph:

1. Reproduce the conflict on a branch, never directly on `develop`.
2. Read both sides and understand the *intent*, not the text.
3. Resolve by keeping both intents where they are complementary; choose a side only
   when they genuinely contradict, and say why in the merge commit.
4. Re-run the full gate after resolving - **a resolution is code nobody has run**.
5. Commit the merge with both parents intact.

The project contains a real, deliberate conflict between PR #18 and PR #19, resolved
by rule 3:

```text
Auto-merging src/main/resources/application.yml
CONFLICT (content): Merge conflict in src/main/resources/application.yml
Automatic merge failed; fix conflicts and then commit the result.
```

Evidence: `docs/evidence/merge-conflict/merge-conflict-output.txt`.

---

## 8. Tags and the release baseline

Two annotated tags, both signed-shape (annotated with a message) rather than
lightweight, so the tag carries authorship and intent:

| Tag | Points at | Meaning |
| --- | --- | --- |
| `v0.1.0-mvp` | `develop`, after PR #7 | All six frozen capabilities implemented and demonstrable; 31 unit/API tests and 5 Selenium journeys green |
| `v1.0.0` | `main`, at the release merge | The release baseline: the full lifecycle, from commit to provisioned node |

```bash
$ git for-each-ref --format="%(refname:short) | %(objecttype) | %(taggerdate:short)" refs/tags
v0.1.0-mvp | tag | 2026-10-18
v1.0.0     | tag | 2026-10-18
```

An annotated tag is a real object with its own message, so `git show v0.1.0-mvp`
explains what the tag means. A lightweight tag is a bare pointer with no room to say
anything - fine for a bookmark, wrong for a release, because "what is in 0.1.0?"
then has to be reconstructed from the graph.

### The release procedure, and why it is a branch

```text
release/v1.0.0 from develop
  → the report pack and the CHANGELOG land here
  → merged into main with --no-ff, then tagged v1.0.0
  → merged back into develop with --no-ff
```

The release needed two things that must not happen on `develop` mid-sprint: the
CHANGELOG brought up to date, and a state frozen long enough to tag and verify. A
release *branch* buys both, and merging it back means `develop` is never behind
`main` - the failure mode of releasing straight from `develop` is that the next
`develop` merge silently reverts the release commit.

`main` is therefore not "the newest code" - it is "the newest *released* code", which
is the only definition that makes it useful during an incident.

---

## 9. The composition of the history, stated honestly

Anyone running the commands in this chapter will notice something, and it is better
to say it here than to let a reader wonder whether they are being misled.

```bash
$ git log --date=iso --format="%h %ad %s" develop | tail -3
5c56453 2026-10-18 19:12:03 +0100 docs(CRFD-110): add the collaboration policy...
dfea120 2026-10-18 19:12:03 +0100 Merge pull request #1 from crfd/...
...
```

**Every commit is dated the same day.** The application, the tests, the container,
the pipeline and the playbooks were built and verified first, as a working system;
the history was then laid down against the finished tree, branch by branch, with the
defects deliberately reintroduced and then fixed on their own branches so that the
`fix` commits are real repairs of real code and not annotations.

What is genuine, and checkable:

- **Every diff.** `git show c97ae88` is the actual change that repaired the write
  path; the parent of that commit is the code that produced the failing Selenium run
  in `docs/evidence/selenium-defect-crfd-117/`.
- **Every branch and merge commit**, with the PR number and the branch name in the
  message, and both parents recorded.
- **The conflict**, reproduced and resolved with the markers and the resolution
  captured.
- **The tags**, and that the tagged tree is the tree that was verified: the suite
  was run against the resolved tip, not against a hypothetical one.
- **The regression tests failing against the pre-fix tree**
  (`docs/evidence/regression-tests-fail-before-fix.txt`), which is the single
  strongest check on the whole story: if the "before" code were not genuinely
  broken, those four tests would pass.

What is a reconstruction, and stated as such:

- **The commit dates.** Every commit carries the date it was written during this
  reconstruction rather than a date from a 15-week calendar. Inventing spread-out
  timestamps would have produced a history that *looked* like a project artefact
  without being one, which is a worse lie than a compressed timeline.
- **The reviewer.** The review comments in `docs/pull-requests/README.md` were
  written by the same author taking the reviewer's position. The comments are
  substantive - several produced real changes - but there was no second person.

Both of these are recorded again in `docs/16-limitations-and-future-enhancements.md`.

---

## 10. Checking all of this yourself

```bash
git log --oneline --graph --all                 # the whole shape
git log --first-parent --oneline develop         # the reviewed units
git log --merges --format="%h %s" develop        # the 19 PRs (+ the release merge)
git log --no-merges --format="%h %s" develop     # 25 working commits
git branch --list                                # the 22 branches
git tag -l                                       # the 2 release tags
git for-each-ref --format="%(refname:short)" refs/tags
git show v0.1.0-mvp                              # what the tag means
git show c97ae88                                 # CRFD-117: the repair
git show dd5b16b                                 # CRFD-121: the 404/405 repair
git show e054ea2                                 # the resolved merge, both parents
git diff --stat v0.1.0-mvp v1.0.0                # what the release added
```

That last command is the one worth running first. It answers, in one screen, what
the difference is between "the MVP is feature-complete" and "the MVP is released":
the container, the pipeline, the Selenium gate, the provisioning, the evidence and
the report.

---

## 11. What would change with a GitHub remote

Stated so the substitution is not mistaken for the design:

| Now | On GitHub |
| --- | --- |
| `origin` is a local bare repository | `origin` is the GitHub remote |
| PRs recorded in `docs/pull-requests/` | the same body opened as a real PR; the `--no-ff` merge commit is GitHub's "Merge pull request" |
| Reviews written by the author as reviewer | a second reviewer's comments on the PR diff |
| Branch protection absent | required status checks (the CI job), required review, no force-push on `main` |
| CI run locally, captured in `docs/evidence/` | the same job reported on the PR |
| `git push --follow-tags` | the same, plus a GitHub release generated from the tag |

Nothing about the branching model, the commit convention or the merge strategy
changes. They were chosen so that they do not depend on the hosting.
