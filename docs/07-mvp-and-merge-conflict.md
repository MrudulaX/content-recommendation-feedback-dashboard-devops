# 07 — MVP completion, the merge conflict, and the release baseline

This chapter answers two questions the brief asks directly: **when was the MVP
complete, and how do you know?** and **what happened when two branches collided, and
how was it resolved?** The second is deliberately engineered, and this chapter
explains why.

---

## 1. What "MVP complete" means for this project

The scope was frozen in week 1 (`docs/01`) to six capabilities, each with a stable
backlog identifier. The MVP is complete when all six are implemented, demonstrable
through the UI, and covered by running tests - not when the code compiles.

| Capability | Issue | Backlog items | Implemented in |
| --- | --- | --- | --- |
| Event and data entry | `CRFD-101` | `101`, `101b`, `101c`, `101d` | PR #3 |
| Searchable dashboard | `CRFD-102` | `102`, `102b` | PR #3 |
| Summary indicators | `CRFD-103` | `103`, `103b` | PR #5 |
| Status drill-down | `CRFD-104` | `104`, `104b` | PR #3, PR #10 |
| Alert / exception view | `CRFD-105` | `105`, `105b` | PR #4 |
| Explicit feedback *(added by change request)* | `CRFD-106` | `106` | PR #6 |
| Dashboard UI over all of it | `CRFD-107` | `107` | PR #7 |

That table is the answer to "did you deliver the frozen scope, or did it drift?".
Six of the seven rows map to the frozen scope; the seventh is the one change
request, and it is named as one rather than quietly absorbed.

The brief's capability list also asks for a **status drill-down** and an **alert
view**. The drill-down is the detail endpoint plus the immutable audit timeline; the
alert view is the exception queue. Both are in the table above, and neither was
folded into another capability to make the numbers look tidy.

---

## 2. The `v0.1.0-mvp` baseline

The tag sits on `develop` immediately after PR #7 - the last capability - and before
any of the DevOps work.

```bash
$ git show v0.1.0-mvp
tag v0.1.0-mvp
Tagger: Mrudul <mrudul@example.com>
Date:   ...

CRFD MVP - feature complete

All six frozen capabilities are implemented and demonstrable:
  ...
Not yet included: the container image, the Jenkins pipeline, the Selenium quality
gate and the provisioning playbooks, all of which land before v1.0.0.
```

**What was verified at that point**, and nothing more is claimed:

| Check | Command | Result |
| --- | --- | --- |
| Build | `mvn -B clean package` | `BUILD SUCCESS` |
| Unit and API tests | `mvn -B test` | 31 passed |
| Selenium journeys | `mvn -B -P selenium verify` | 5/5, run twice - once against the local JAR, once against the containerised application |

**Caveat, stated because the tag is a claim.** At the MVP tag the Selenium journeys
passed, but the `EVT-` defect (CRFD-117) was still present in the tipped tree and
had *not yet* been found: the journeys were run and passed against a build that
happened to produce compliant keys, and it took a run against a differently-seeded
instance to expose the inconsistency. The tagged tree is therefore
"feature-complete and green", not "defect-free" - which is what a tag means, and
conflating the two is how a release gets a reputation it cannot keep.

---

## 3. What the tag does *not* contain

Being explicit about the gap between "MVP" and "delivered" is the point of the two
tags. `v0.1.0-mvp` has no:

- container image, no Compose stack, no Nginx edge;
- Jenkins pipeline, no controller-as-code, no quality gate;
- Selenium **gate** - the tests exist and pass, but nothing stops a failing run from
  reaching a deploy;
- provisioning: no Ansible roles, no idempotency evidence, no rollback;
- report pack or captured evidence.

The application at `v0.1.0-mvp` runs. It cannot be built into an artefact, gated,
deployed, verified or recovered by anyone else. That difference is the entire subject
of the rest of the report.

---

## 4. From MVP to release: what the release added

```bash
$ git diff --shortstat v0.1.0-mvp v1.0.0
 98 files changed, 13087 insertions(+), 16 deletions(-)
```

```bash
$ git diff --name-only --diff-filter=A v0.1.0-mvp v1.0.0 | cut -d/ -f1-2 | sort -u
.dockerignore
Dockerfile
Jenkinsfile
docker-compose.yml
docker-compose.mysql.yml
infra/ansible
infra/jenkins
infra/nginx
infra/selenium
scripts/*
src/test                ← 3 189 lines of the total
```

Everything the release added is either **the toolchain** (`Dockerfile`, `Jenkinsfile`,
`infra/*`, `scripts/*`) or **the tests** (`src/test`, the single largest addition). The
application itself changed by three defect repairs. That ratio is the honest shape of
this project: the MVP was already there; what the DevOps work added was the ability
to build, gate, ship, verify and recover it.

---

## 5. `v1.0.0` as the release baseline

```text
release/v1.0.0  ─┬─►  main      merge --no-ff, then tag v1.0.0
                 └─►  develop   merge --no-ff  (so develop is never behind main)
```

`main` is the release branch. It is not "the newest code" - it is "the newest
*released* code", which is the only definition of `main` that is useful during an
incident, when the question is "what is in production and can I rebuild it?".

The release branch is not a formality. `release/v1.0.0` is where the last fix
landed. End-to-end verification of the candidate found that the minted event key
did not match the shape the API catalogue documents - `EVT-5794A34B-98B` instead of
`EVT-7F3A91C4` - a contract gap no test had pinned. The failing contract test, the
generator fix and the verification evidence were committed on the release branch,
and only then was the tag cut, so `v1.0.0` points at a state that was *verified*,
not merely a state that was finished. `docs/11` §10 records it.

The baseline is reproducible from the tag alone:

```bash
git checkout v1.0.0
mvn -B clean package                     # 32 tests
./mvnw -B -P selenium verify             # 5 journeys
docker build -t crfd:1.0.0 .
docker run -d -p 18080:8080 crfd:1.0.0
scripts/smoke-test.sh http://localhost:18080   # 11 checks
```

and `docker inspect crfd:1.0.0` answers a question `docker ps` cannot - not just
"which image is running" but "which source revision produced it":

```text
Env=[... "APP_VERSION=1.0.0-SNAPSHOT", "BUILD_REVISION=local"...]
```

---

## 6. The merge conflict

### 6.1 It was staged on purpose

The brief asks for a merge conflict to be *created* and *resolved*. A conflict that
arrives by accident in a real repository is a nuisance; the same conflict rehearsed
deliberately is the single highest-value five minutes in a Git workflow, because
resolving one under deadline pressure for the first time is how a "resolved" merge
silently drops a colleague's change.

So two branches were cut from the same `develop` tip, both editing the same region of
`src/main/resources/application.yml`:

```text
91dc232  develop
   ├── feature/CRFD-103-threshold-documentation   3a9cd32   → merged first as PR #18
   └── feature/CRFD-105-alert-threshold-documentation   00f77c8   → conflicts
```

The subject matter was chosen carefully: **comments above the `crfd.thresholds`
block**. That makes the conflict realistic - two people documenting the same
configuration - while guaranteeing that no *value* is at stake, so the resolution can
be verified by inspection and does not silently change behaviour.

### 6.2 The conflict, as it happened

```text
$ git merge --no-ff feature/CRFD-105-alert-threshold-documentation
Auto-merging src/main/resources/application.yml
CONFLICT (content): Merge conflict in src/main/resources/application.yml
Automatic merge failed; fix conflicts and then commit the result.

$ git status --short
UU src/main/resources/application.yml
```

`UU` - both modified - is the state that matters. Step 1 of the procedure in
`BRANCHING.md` §6 is to stop: a `UU` file is not text to be tidied, it is a question
about intent.

The file, mid-conflict:

```yaml
93      async-enabled: ${CRFD_ASYNC_ENABLED:true}
94
95  <<<<<<< HEAD
96    # Thresholds the pipeline and the alerting rules evaluate. Every one of them is
97    # overridable by an environment variable, so a deployment can tune its own
98    # sensitivity without a rebuild.
99  =======
100   # Alert thresholds. A breach raises an exception on the queue rather than failing
101   # a request: these are quality signals, not availability ones. See docs/05 for
102   # the full list of alert codes.
103 >>>>>>> feature/CRFD-105-alert-threshold-documentation
104   thresholds:
105     low-confidence: ${CRFD_LOW_CONFIDENCE:0.30}
```

### 6.3 Understanding the intent before touching the text

The two sides are **not** in conflict about anything:

| Side | The question it answers |
| --- | --- |
| `HEAD` (CRFD-103, PR #18) | Why is every threshold environment-overridable? |
| Branch (CRFD-105, PR #19) | What does breaching a threshold actually do? |

Two true statements about the same block of configuration. Choosing either side would
delete a correct sentence; a "resolution" that keeps one is a resolution that makes
the file *less* accurate than before the merge. This is the common case, and the
reason rule 3 of the policy says keep both intents where they are complementary.

### 6.4 The resolution

```yaml
  # Thresholds the pipeline and the alerting rules evaluate.
  #
  # Every one of them is overridable by an environment variable, so a deployment
  # can tune its own sensitivity without a rebuild.
  #
  # A breach raises an exception on the queue rather than failing a request: these
  # are quality signals, not availability ones. See docs/05 for the full list of
  # alert codes.
  thresholds:
    low-confidence: ${CRFD_LOW_CONFIDENCE:0.30}
    high-latency-ms: ${CRFD_HIGH_LATENCY_MS:500}
    critical-latency-ms: ${CRFD_CRITICAL_LATENCY_MS:1500}
    stale-event-hours: ${CRFD_STALE_EVENT_HOURS:24}
    dismiss-rate-threshold: ${CRFD_DISMISS_RATE_THRESHOLD:3}
    dismiss-window-minutes: ${CRFD_DISMISS_WINDOW_MINUTES:60}
    low-rating-threshold: ${CRFD_LOW_RATING_THRESHOLD:2}
    minimum-success-rate: ${CRFD_MINIMUM_SUCCESS_RATE:0.95}
```

Three things about this:

1. **Every marker is gone.** `grep -c '<<<<<<<\|=======\|>>>>>>>'` on the file returns
   0. A marker left in a YAML file is not a syntax error - YAML will happily treat
   `<<<<<<< HEAD` as a key - so it will not fail loudly. It will fail quietly, later.
2. **No value changed.** The whole conflict is inside comments, which is why the
   resolution can be judged by reading it rather than by trusting it.
3. **The comments were rewritten, not concatenated.** The two blocks were merged into
   one statement instead of being pasted one after the other. A resolution that reads
   like two people shouting over each other is a resolution that will be deleted by
   the next person who edits the file.

### 6.5 Verifying the resolution

Step 4 of the policy: **a resolution is code nobody wrote, so run the gate.**

```bash
$ git add src/main/resources/application.yml
$ git commit -F /tmp/merge-msg.txt
$ mvn -B -o test
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0  -- AlertServiceTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0  -- EventServiceTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0  -- EventApiTest
[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

And both parents are recorded, so the history shows a real merge:

```bash
$ git log -1 --format="%h parents=%p"
e054ea2 parents=e24ece6 00f77c8
```

A resolution committed on top of a branch, with one parent, would look identical in
`git log --oneline` and would have destroyed the record of which branches produced the
file. The two-parent merge commit is the evidence that a merge happened.

### 6.6 The evidence

`docs/evidence/merge-conflict/merge-conflict-output.txt` holds, verbatim:

- the two branch tips and the shared base;
- the `git merge` output, including `CONFLICT (content)`;
- `git status --short` showing `UU`;
- the conflict markers with their surrounding context;
- the resolved block, and the check that no marker remains;
- the resolution commit and both of its parents.

---

## 7. Three questions this chapter should be able to answer

**"Why stage a conflict instead of waiting for a real one?"**

Because a real one is not reproducible on demand, and the brief asks for the skill to
be demonstrated. The staged conflict exercises the same thing a real one does -
reading intent out of two diffs and keeping both - with the advantage that the result
can be checked against a known-good answer. The two branches are not throwaway: PR
#18 and PR #19 both document real configuration.

**"What would you have done if the two sides contradicted each other?"**

Picked one, and put the reasoning in the merge commit body - which is exactly what was
done here, minus the picking: the merge commit records why both sides were kept. The
failure mode to avoid is a resolution that keeps whichever side happened to be easier
to keep, with no note explaining the choice; that is how a feature disappears and
nobody notices for a month.

**"How do you know the resolution didn't break anything?"**

The suite was run against the resolved tree (31 green), and separately the pre-fix
tree was verified to be genuinely broken
(`docs/evidence/regression-tests-fail-before-fix.txt`). Together those two runs
bracket the change: the tests can fail, and after the change they pass.
