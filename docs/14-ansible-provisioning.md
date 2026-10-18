# 14 — Provisioning a clean node with Ansible

## 1. What has to be proved

The brief asks for a specific chain of claims, and each one is only meaningful if
it is demonstrated rather than described:

| Claim | Demonstrated by |
| --- | --- |
| A **clean node** can be provisioned from nothing | `crfd-node` starts as SSH + a Docker engine and nothing else |
| Provisioning is **idempotent** | A second identical run reports **`changed=0`** |
| The node is **healthy** afterwards | `healthcheck.yml` probes the running application |
| A release can be **deployed** | `deploy.yml` moves `0.1.0 → 1.0.0` |
| A release can be **rolled back** | `rollback.yml` returns the node to `0.1.0` |
| Recovery is **repeatable** | The recorded rollback point names the release just left, so the node can roll forward again |

Standing up a spare VM to prove this would make the evidence irreproducible by
anyone reading the report. So the demo stands up two throwaway containers instead,
and the *playbooks are identical to the ones that would run against real hosts*:

```bash
scripts/ansible-demo.sh          # 5 playbooks, 5 evidence logs
scripts/ansible-demo.ps1 -KeepRunning   # the Windows runner (leaves the node up to inspect)
```

## 2. The two containers

```bash
docker build -f infra/ansible/node/Dockerfile -t crfd-node:latest infra/ansible/node
docker build -f infra/ansible/Dockerfile.controller -t crfd-ansible:latest infra/ansible
```

| | `crfd-node` | `crfd-ansible` |
| --- | --- | --- |
| Base | `docker:27-dind` (Alpine) | `python:3.12-alpine` |
| Contains | SSH daemon, a real Docker engine, and **nothing else** | ansible-core, `community.general`, `community.docker`, the Docker SDK, `sshpass` |
| Role | The provisioning **target** — a clean node | The pinned **controller** |
| Why | It is the closest thing to a bare VM that can be created and destroyed on one machine | Pinned versions mean "run the playbooks" is reproducible on a laptop with no Python toolchain |

The node is deliberately *not* a custom-prepared image. If the node image contained
the application's dependencies, the playbooks would prove nothing.

The controller being pinned matters as much: `ansible-core` is version-sensitive,
and an unpinned controller means the evidence is only valid on the machine that
produced it.

## 3. The inventory, and a precedence trap worth recording

```
infra/ansible/
├── ansible.cfg
├── Dockerfile.controller
├── entrypoint.controller.sh     stages the mount (see §7)
├── node/                        the disposable clean node
├── inventory/
│   ├── hosts.example.ini        committed example for real hosts
│   ├── hosts.demo.ini           the disposable node (connection details only)
│   ├── demo-overrides.yml       demo values, applied as extra vars
│   └── group_vars/all.yml       production defaults
├── roles/{common,docker,crfd_app,nginx}/
└── playbooks/{site,provision,deploy,healthcheck,rollback}.yml
```

**The trap.** The first draft put the demo's values in the inventory file:

```ini
[crfd:vars]
app_pull_image=false        # ← silently ignored
nginx_enabled=false         # ← silently ignored
```

They were ignored, and the failure was confusing: the playbook tried to pull
`crfd:0.1.0` from Docker Hub, an image that only exists locally.

The cause is Ansible's variable precedence. Variables written in an inventory
*file* (`[group:vars]`) sit **below** `inventory/group_vars/all.yml`, so the
production defaults (`app_pull_image: true`, `nginx_enabled: true`) won. Two
settings that look like configuration were dead code.

The fix is structural, not a nudge: demo values moved to `inventory/demo-overrides.yml`
and are applied as **extra vars**, which outrank everything:

```bash
ansible-playbook -i inventory/hosts.demo.ini \
                 -e @inventory/demo-overrides.yml \
                 -e app_version=0.1.0 playbooks/site.yml
```

`hosts.demo.ini` now contains *only* connection details, with a comment explaining
why the tempting place to put the overrides is the wrong one. A value that is
silently ignored is worse than a missing value, because it looks configured.

## 4. The roles

| Role | Responsibility | Notable behaviour |
| --- | --- | --- |
| `common` | Baseline: timezone, deploy user and group, directory structure, an identity banner | Uses an OS-specific package list (`vars/alpine.yml`, `vars/debian.yml`); **detects** rather than assumes |
| `docker` | Container host | **Detects an existing engine and no-ops.** It does not reinstall Docker on a node that already has one, which is what makes the role safe to run against a real host |
| `crfd_app` | Create the network and volume, record the outgoing release, render an auditable env file, deploy the container, wait for health, confirm the UI is served | The rollback point is written **only when the version changes** — that is what keeps a repeat run at `changed=0` |
| `nginx` | Edge reverse proxy | Disabled for the demo (a container has no init system to supervise it) |

### The `crfd_app` role, in order

```yaml
1  ensure the application network exists
2  ensure the data volume exists  ← so a container replace never loses data
3  list images; is the requested release present locally?
4  inspect the container currently deployed
5  determine the version currently running
6  record the outgoing release     ← ONLY if the version actually changes
7  append the transition to the release history
8  render /etc/crfd/crfd-app.env   ← an on-disk record of the configuration
9  deploy the container on the requested release
10 wait until /api/v1/health reports UP
11 assert the dashboard UI is reachable, and that it is the dashboard
```

Two of these carry most of the weight.

**Step 2 and step 9 together** are why "replace the container" is safe: the data
volume is created before the container and mounted by it, so removing and
recreating the container loses nothing.

**Step 6 is the idempotency mechanism.** The task is:

```yaml
- name: Record the outgoing release for rollback
  ansible.builtin.copy:
    dest: "{{ crfd_config_dir }}/previous-release.txt"
    content: "{{ crfd_current_version }}\n"
  when:
    - crfd_current_container.exists
    - crfd_current_version != app_version
```

If the running version is already the requested version, nothing is written — so a
repeat run reports `changed=0`, *and* the recorded rollback point stays meaningful.

**Step 11 asserts on content, not on a status code.** It fetches the dashboard and
checks for the title and the script bundle, because an HTTP `200` could equally be
a proxy's error page. This is the same reasoning that makes the pipeline's
write→read smoke test necessary: reachability is not correctness.

## 5. The measured result

`docs/evidence/ansible-summary.txt`, condensed from the five full logs:

| Step | Playbook | Recap | Reading |
| --- | --- | --- | --- |
| 1 | `site.yml -e app_version=0.1.0` | `ok=30 changed=9 failed=0 skipped=17` | Clean node becomes a serving CRFD node |
| 2 | `site.yml -e app_version=0.1.0` (identical) | **`ok=30 changed=0 failed=0 skipped=17`** | **Idempotency** |
| 3 | `deploy.yml -e app_version=1.0.0` | `ok=20 changed=4 failed=0 skipped=2` | Release moves; rollback point recorded |
| 4 | `healthcheck.yml` | `ok=7 changed=0 failed=0 skipped=1` | Read-only verification changes nothing |
| 5 | `rollback.yml` | `ok=23 changed=3 failed=0 skipped=3` | Recovery to the recorded release |

Step 2 is the answer to the brief. `changed=0` is not a claim in a document; it is
Ansible's own count of the tasks that did anything.

Step 5 ends with:

```text
TASK [Report the recovered state]
ok: [crfd-node] => msg: RECOVERED crfd-node -> crfd:0.1.0, health=UP
```

### The rollback bookkeeping

```text
previous-release.txt: 1.0.0
release-history.log:
  2026-10-18T12:44:43Z  0.1.0 -> 1.0.0  (by ansible, host=crfd-node)
  2026-10-18T12:45:20Z  1.0.0 -> 0.1.0  (by ansible, host=crfd-node)
```

After rolling back, the file names `1.0.0` — the release just left behind. Rolling
back therefore leaves the node able to roll *forward*. A rollback that leaves a
node unable to move forward again is a trap dressed as a fix.

The history file is append-only, so the transitions are on the record rather than
being reconstructed from memory during an incident.

## 6. Health checks, and the mistake they exposed

`healthcheck.yml` probes each node and asserts it is **serving**. The first version
asserted `status == 'UP'` and failed with:

```text
crfd-node reports status=DEGRADED
```

Nothing was wrong. The node was serving every request; its pipeline success rate
was `0.93` against a `0.95` objective. **A business metric was being used to decide
whether a node could serve traffic** — the mistake described in
`docs/11` §5, committed a second time in a different place.

Corrected, the playbook distinguishes the two questions:

```yaml
# readiness, not perfection
- ansible.builtin.fail:
    msg: "{{ inventory_hostname }} reports status={{ node_status }}; a serving node must report UP or DEGRADED"
  when: node_status not in ['UP', 'DEGRADED']
```

and then probes the SLO gate **for the record, without failing**:

```text
HEALTH crfd-node -> status=UP version=1.0.0-SNAPSHOT container=running slo=WITHIN_OBJECTIVE (successRate=0.9595)
```

The `HEALTH … slo=…` line is the honest shape of a health check: assert the thing
that must block a deployment, report the thing that must not.

## 7. The controller: three bugs, and what they were really about

Getting the demo green took three fixes, each of which is a general lesson.

### (a) Ansible silently ignored `ansible.cfg`

```
ERROR! the role 'common' was not found in
/ansible/playbooks/roles:/root/.ansible/roles:/usr/share/ansible/roles:...
```

The roles were exactly where they should be. The problem was one line earlier in the
run, and it was only a warning:

```
[WARNING]: Ansible is being run in a world writable directory (/ansible),
ignoring it as an ansible.cfg source.
```

The project directory is bind-mounted into the controller, and a mount from a
Windows host arrives world-writable. **Ansible refuses to read `ansible.cfg` from a
world-writable working directory** — so `roles_path` and `inventory` were never
loaded, and the run died with an error naming a completely different problem.

The fix is `infra/ansible/entrypoint.controller.sh`, which stages the mount into a
private copy before running anything:

```bash
rm -rf /work && mkdir -p /work
cp -a /ansible/. /work/
chmod -R go-w /work      # kill world/group write so the config check passes
cd /work
exec /bin/bash -lc "$*"
```

Every path in the playbooks is relative, so the playbooks cannot tell the
difference — which is precisely the point. Following the misleading error message
would have meant rearranging roles that were never misplaced.

The lesson generalises: **a warning that changes behaviour is an error.** Ansible
printed it, continued, and produced a failure with the wrong cause.

### (b) The demo node could not enforce CPU and memory limits

```
failed to create task for container: unable to apply cgroup configuration:
cannot enter cgroupv2 "/sys/fs/cgroup/docker" with domain controllers
```

The node's Docker engine is itself running inside a container, and a nested engine
cannot apply cgroup controller configuration. Verified directly: the same image
starts fine with limits removed, and fails with them.

The fix is to make the limits **opt-in** rather than to delete them, because they
are genuinely wanted on a real node:

```yaml
memory: "{{ app_memory_limit if (app_memory_limit | default('') | length > 0) else omit }}"
cpus:   "{{ app_cpu_limit   if (app_cpu_limit   | default('') | length > 0) else omit }}"
```

`group_vars/all.yml` keeps `768m` / `1.0`; `demo-overrides.yml` blanks both, and an
empty value omits the parameter instead of passing `0` (which would mean "no limit"
only by accident). This is a property of nested Docker, not a workaround for a
playbook bug, and it is stated as such.

### (c) Two smaller ones

**`community.general.timezone` failed on Alpine** with *"Failed to find required
executable `setup-timezone`"* — that script ships in `alpine-conf`. Fixed by adding
the package to the Alpine baseline list **and reordering the role** so packages are
installed before anything is configured with them. The original order was
configure-then-install-the-tool, which cannot work on any platform that needs the
tool.

**The provisioning banner broke idempotency.** It embeds `ansible_date_time`, which
differs on every run, so the `copy` reported `changed` forever and the second run
could never be `changed=0`. Fixed with `force: false`, which is also semantically
righter: the banner records when the node was **first** provisioned.

## 8. Against the brief

| Requirement | Where |
| --- | --- |
| Provision a **clean node** | `crfd-node` = SSH + Docker only; `playbooks/site.yml` turns it into a CRFD node |
| Puppet **or** Ansible | Ansible, `community.general` + `community.docker`; controllers and versions pinned (`Dockerfile.controller`, `ansible.cfg`) |
| **Idempotency evidence** | Step 2 recap: `changed=0`; `docs/evidence/ansible-02-idempotency-second-run.log` |
| **Health check** | `healthcheck.yml`; readiness `UP`/`DEGRADED` accepted, SLO reported separately |
| **Rollback / recovery** | `rollback.yml` → `RECOVERED crfd-node -> crfd:0.1.0, health=UP`; `previous-release.txt` + `release-history.log` |
| Roles, not one script | Four roles with clear responsibilities; `docker` detects and no-ops on an existing engine |
| Real evidence, not assertions | Five full play logs plus `ansible-summary.txt`, all reproducible with one command |

## 9. Why Ansible rather than Puppet here

Both were permitted. Ansible was chosen for reasons that fit this project, and the
trade-off is stated honestly:

| | Ansible | Puppet |
| --- | --- | --- |
| Architecture | **Agentless** — SSH and Python | Agent + Puppet Server |
| Fits this project | The demo node has SSH and nothing else, so a *clean* node is genuinely clean | Provisioning a clean node requires installing the agent first |
| Ordering | Tasks run top to bottom; a playbook reads as a procedure | Declarative resource graph; **better at expressing convergence** |
| Drift correction | Converges when a playbook runs | Continuously corrects, which is a real advantage at fleet scale |
| Threshold | Comfortable to a few hundred hosts | Comfortable at thousands |

The honest summary: puppet/Chef's continuous convergence is genuinely better at
fleet scale, and Ansible wins here because it needs nothing on the target. For a
project whose central claim is *"here is a clean node, now watch it being
provisioned"*, agentless is not a preference — it is what makes the claim
demonstrable.

## 10. Known limitations

- **One node.** The rolling-deployment machinery (`serial: 1`,
  `max_fail_percentage: 0`) is written and correct but exercises a fleet of one, so
  the interesting failure mode — a node failing mid-rollout — is not demonstrated.
- **A password-authenticated demo node.** `sshpass` with a password is used because
  `docker:dind` needs a trivial credential for a throwaway container. Real
  inventories use keys; `hosts.example.ini` shows that shape.
- **`nginx_enabled` is off for the demo.** A container has no init system to
  supervise an Nginx process, and the `nginx` role manages a service. The role is
  real and used by the production defaults, but it is exercised on a VM or a
  systemd-enabled node, not on the disposable container.
- **No `ansible-lint` / molecule in the pipeline.** The playbooks are reviewed and
  exercised, not lint-enforced. Noted in `docs/16`.
