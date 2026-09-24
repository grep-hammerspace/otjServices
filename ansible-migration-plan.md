# Box configuration as code (Ansible) — plan

**Status:** draft for review · 2026-09-24 · branch `plan/deployment-uplift`

**Goal:** nobody opens an SSM session on the EC2 box to change it again. Everything the box has
(packages, users, Caddy, Tailscale, Quadlets, env files, the deploy script) is declared in this
repo, checked on every PR, and applied by CI on every merge to `master`. The box stays the
deployment target. Nothing moves to Fargate, and the monthly cost stays about the same (§12).

**Scope:** the **inside** of the box. AWS resources stay in CDK. Cloudflare and Tailscale admin
settings stay manual for now (§13 lists them as follow-ups).

> Sections are numbered so review comments can point at them. Decisions are **D1–D4** in §1.
> Claims not yet checked against the live box or a real run are marked *(verify)*.

---

## Contents

1. [Decisions needed](#1-decisions-needed)
2. [Ansible in five minutes, for this repo](#2-ansible-in-five-minutes-for-this-repo)
3. [What is manual today](#3-what-is-manual-today)
4. [Design](#4-design)
5. [Repository layout](#5-repository-layout)
6. [Roles](#6-roles)
7. [Secrets](#7-secrets)
8. [CI: checks on every PR](#8-ci-checks-on-every-pr)
9. [CD: deploy on merge, and ops without a shell](#9-cd-deploy-on-merge-and-ops-without-a-shell)
10. [Migrating the live box](#10-migrating-the-live-box)
11. [Rebuilding from nothing](#11-rebuilding-from-nothing)
12. [Cost](#12-cost)
13. [Out of scope, and follow-ups](#13-out-of-scope-and-follow-ups)
14. [Risks](#14-risks)
15. [What gets deleted, and docs to update](#15-what-gets-deleted-and-docs-to-update)

---

## 1. Decisions needed

### D1 — Where Ansible runs

| Option | How | For | Against |
|---|---|---|---|
| **A. On the box, against itself** (`ansible-playbook -c local`), started by CI through `ssm:SendCommand` | The playbook ships inside the app image, and a small bootstrap script on the box extracts it and runs it | No new AWS permissions (CI already has `SendCommand`). No S3 bucket. The config version always matches the image version. | `ansible-core` has to be installed on the box |
| B. On the GitHub runner, connecting through the `community.aws.aws_ssm` connection plugin | Ansible drives the box remotely over SSM | The textbook controller/target split | The plugin needs an S3 bucket to transfer files, extra IAM, and is slow (one SSM round trip per task) |

**Recommendation: A.**

### D2 — Where secrets live

| Option | Cost | Notes |
|---|---|---|
| **A. SSM Parameter Store `SecureString`** (standard tier, AWS-managed `aws/ssm` key) | **$0** | Encrypted, access controlled by IAM, audited by CloudTrail. No automatic rotation, which nothing here would use anyway. |
| B. Secrets Manager | $0.40 per secret per month | Adds automatic rotation and cross-account sharing, and neither is needed. |

**Recommendation: A.** Both are read with the instance role, and both are pulled when a unit
starts, never written by Ansible (§7).

### D3 — Where Caddy comes from

Today it is the Cloudsmith apt package with `caddy add-package` replacing the binary, then
`apt-mark hold`. `deploy/prod/README.md` spends two pages on how that goes wrong.

| Option | For | Against |
|---|---|---|
| **A. Built in CI with `xcaddy`** (pinned Caddy and `caddy-ratelimit` versions) as a stage of the app Dockerfile, shipped in the image at `/deploy/bin/caddy`, installed by Ansible | Reproducible. The plugin is compiled in rather than bolted on. A rollback carries its Caddy with it. CI can `caddy validate` the real Caddyfile with the real binary. Dependabot bumps the builder image. | The app image grows by ~45 MB. Ansible has to own the `caddy` user and systemd unit that the apt package used to provide. |
| B. Keep the apt package, and have Ansible do the Cloudsmith repo, `add-package` and hold | Smallest change | Encodes the most fragile part of the current setup. `add-package` downloads from Caddy's build service at run time, so it isn't reproducible and fails if that service is down. |

**Recommendation: A.** Do it as its own step (§10, step 7), after the playbook has taken over
the box with option B's behaviour, so the two changes can't be confused with each other.

### D4 — Cloudflare Tunnel now or later

A Tunnel would remove the Origin CA certificate, the Cloudflare IP list in the security group,
and the port 443 clash with `tailscale serve`. With Ansible in place, it is one new role plus a
DNS change.

**Recommendation: later**, as a separate plan (§13). Adopting Ansible should change *how* the box
is configured, not *what* it runs. Mixing the two makes the first `--check --diff` impossible to
read.

---

## 2. Ansible in five minutes, for this repo

Only the parts this plan uses.

| Concept | What it is | Here |
|---|---|---|
| **Playbook** | A YAML file listing which roles to apply to which hosts | `deploy/ansible/site.yml`: one play, `hosts: localhost` |
| **Role** | A folder of related tasks, templates and handlers | `base`, `tailscale`, `caddy`, `app`, … (§6) |
| **Task** | One desired state, e.g. "this package is installed" or "this file has this content" | `ansible.builtin.apt`, `copy`, `template`, `systemd_service`, `uri` |
| **Idempotence** | Running a task twice changes nothing the second time. Tasks describe *state*, not *steps*. | Every CI run applies the playbook **twice** and fails if the second run changed anything (§8) |
| **Handler** | A task that runs only when something **changed**, e.g. "restart Caddy if the Caddyfile changed" | Restarts happen only when that unit's config or image actually changed |
| **Check mode** (`--check --diff`) | A dry run that prints what *would* change, with file diffs | How we adopt the live box safely (§10), and the nightly drift check (§9.3) |
| **Variables** | Values in `group_vars/`, overridable with `-e` | `image_tag`, ports, the Tailscale hostname. **Never secrets** (§7). |
| **`become`** | Run a task as a different user | Root by default; `become_user: otjapp` for the rootless Podman parts |
| **`no_log: true`** | Hides a task's arguments and results from output | On any task that could touch a secret. SSM output ends up in GitHub Actions logs. |
| **Tags** | Labels for running or skipping groups of tasks | `tailnet` and `aws` are skipped in the CI rehearsal (§8.3) |

The commands you'll actually use:

```bash
ansible-playbook -i inventory.ini site.yml --syntax-check
ansible-playbook -i inventory.ini site.yml --check --diff -e image_tag=<sha>   # dry run
ansible-playbook -i inventory.ini site.yml -e image_tag=<sha>                  # apply
ansible-lint                                                                    # style + common mistakes
```

In normal use you won't run these by hand at all. CI runs them on the box for you (§9).

---

## 3. What is manual today

Everything in this table was done through an SSM session, from `deployment-checklist.md` §6 and
`deploy/prod/README.md`. Each row becomes code. The right-hand column is the hard-won lesson that
row's task has to encode. The runbook's warnings turn into comments next to the task that
prevents the problem.

| Manual step | Becomes | Gotcha the task must encode |
|---|---|---|
| Install Tailscale, `tailscale up --hostname=hours-api` | `tailscale` role | Only run `up` when `BackendState != Running`, so re-runs never re-authenticate the live node |
| `tailscale set --operator=…` | Removed | Ansible runs `serve` as root, so no operator is needed |
| `tailscale serve` 8444 → 8945, 8443 → 8946; **443 turned off** | `tailscale` role | **8444 before Caddy binds 443.** Role order in `site.yml` enforces it, and a task asserts nothing but Caddy holds `:443` |
| Install Podman | `base` role | — |
| AWS CLI v2 from the zip | `base` role | Ubuntu's apt copy is v1 and too old for `ecr get-login-password` |
| `useradd otjapp`, `enable-linger` | `otjapp` role | Linger must exist before any `systemctl --user` task, or they fail with no user bus |
| Paste `deploy.sh` and the two templates into `~/otj-deploy/` | `app` role, sourced from the image | Trailing whitespace from pasting (issue #40) can't happen, because files are copied byte for byte |
| Write `~/otj-hours-api.env` and `~/otj-admin-api.env` | `app` role + `render-env` (§7) | Secrets never pass through Ansible |
| Cloudsmith repo, `apt install caddy`, `add-package`, `apt-mark hold`, restart | `caddy` role (D3) | Verify the running binary has `http.handlers.rate_limit`, not just the file on disk |
| Install the Origin CA pair, `640 root:caddy` | `caddy` role, from Parameter Store (§7) | Caddy runs as `caddy` and must be able to read the key |
| Install the Caddyfile, validate **as `caddy`**, reload | `caddy` role | Validating as root creates `/var/log/caddy/access.log` as `root:root` and the reload dies. The task sets the log file's owner explicitly and validates with `become_user: caddy`. |
| Hand-push files with `push-file.sh` | Deleted | — |

---

## 4. Design

### 4.1 Two layers, one artifact

```
image otj-hours-api:<sha>
  /app/app.jar
  /deploy/ansible/...          ← the playbook, roles, templates, group_vars
  /deploy/bin/caddy            ← CI-built Caddy (D3 = A, from step 7)
  /deploy/Caddyfile
```

Everything the box needs for a release is inside the image for that release, which is #40's
idea taken all the way. Rolling back to an old SHA brings back that SHA's Quadlets, Caddyfile,
Caddy binary and playbook together.

### 4.2 `otj-converge`, the one script on the box

A small bash script at `/usr/local/sbin/otj-converge`. It is installed once by hand (§10, step 2),
or by cloud-init on a rebuilt box, and **after that the playbook manages it**, so it updates
itself.

```
otj-converge <sha> [--check]
  1. ensure ansible-core is installed (apt; noop after first run)
  2. ECR login + pull <repo>:<sha> as otjapp            (rootless storage, as today)
  3. extract /deploy from the image → /opt/otj/releases/<sha>/  (keeps the last 5)
  4. ansible-playbook -c local -i localhost, site.yml -e image_tag=<sha> [--check --diff]
  5. exit with ansible's status
```

It is written as plain, top-to-bottom bash so it is easy to read in an emergency.
`--check` is how the nightly drift job and the adoption step (§10) run it.

### 4.3 Why restarts only happen when something changed

The image tag is templated into each Quadlet (`Image=…:<sha>`). A new SHA changes the Quadlet
file, which notifies the handler that restarts that unit. The same SHA converged twice changes
nothing, so nothing restarts. So "redeploy", "re-apply config" and "fix drift" are all the same
command, and each only touches what actually differs.

### 4.4 Health and safety checks at the end of every run

The `verify` role runs last on every apply:

- `GET 127.0.0.1:8945/health` and `:8946/health`, with retries (replaces `deploy.sh`'s loop).
- `caddy list-modules` from the **running** service includes `http.handlers.rate_limit`.
- **No wildcard listeners except the expected ones.** `ss -Hltn` must show `0.0.0.0:443` and
  `[::]:443` (Caddy) and nothing else on `0.0.0.0` or `[::]`. Ubuntu's AMI ships with `sshd` on
  `0.0.0.0:22`: the security group blocks it and nothing uses it, but it still listens. `base`
  disables it (see §6), and until that step lands, the check allows `:22`. This is AGENTS.md's loopback rule
  for 8945 and 8946 turned into a check that fails the deploy.
- `tailscale serve status --json` shows exactly the 8443 and 8444 mappings and nothing on 443.

---

## 5. Repository layout

```
deploy/
  ansible/
    ansible.cfg              # inventory, roles_path, stdout callback = yaml, no host key checks
    inventory.ini            # localhost ansible_connection=local
    site.yml                 # the one play; role order matters (§6)
    group_vars/all.yml       # ports, hostnames, image repo, paths — nothing secret
    group_vars/ci.yml        # overrides for the PR rehearsal (§8.3)
    roles/
      base/  otjapp/  tailscale/  caddy/  app/  verify/
  bin/
    otj-converge             # §4.2
  Caddyfile                  # moved from deploy/prod/
  podman-compose.yaml, bootstrap.sh, shell.nix, README.md   # self-host path, unchanged
docker/otjService.Dockerfile # gains a caddy build stage and COPY deploy/ → /deploy
```

`deploy/prod/` is deleted once its contents have moved (§15).

---

## 6. Roles

In `site.yml` order. The order is significant: `tailscale` has to move off 443 before `caddy`
binds it.

### `base`
- apt: `podman`, `uidmap`, `jq`, `unzip`, `curl`, `ansible-core`, `unattended-upgrades`
  (security updates only, **automatic reboot off**, since a reboot is an API outage).
- AWS CLI v2: download a **pinned** version's zip, check it against a checksum in `group_vars`,
  unarchive, install (`creates:` guards re-runs).
- `/usr/local/sbin/otj-converge` from `deploy/bin/`, so the script updates itself.
- journald size cap (`SystemMaxUse=500M`), since there is nothing else stopping logs filling the
  20 GB disk.
- Disable and mask `ssh.socket` and `ssh.service` (Ubuntu 24.04 starts sshd through a socket).
  Access is SSM only, and there is no key and no open port. For break-glass, EC2 Serial Console
  still works if SSM ever fails. This is its own PR, after §10 step 4, so the wildcard check can
  drop its `:22` exception.

### `otjapp`
- User `otjapp` (shell `/bin/bash`, home `/home/otjapp`), `loginctl enable-linger`.
- Records the user's UID as a fact, so later roles can set `XDG_RUNTIME_DIR=/run/user/<uid>`
  for `systemctl --user`. Without it, `systemd_service` with `scope: user` can't find the user's
  service manager and fails. This is the most common Ansible-plus-rootless-Podman trap.

### `tailscale` (tag `tailnet`)
- Tailscale apt repo (`deb822_repository` with the signed-by key) and package.
- `tailscale up` **only if** `tailscale status --json` reports anything other than `Running`.
  On the live box this never fires. On a rebuilt box it uses an auth key from Parameter Store
  (§11), with `no_log: true`.
- `serve`: turn off `--https=443`, apply `--https=8444 → 127.0.0.1:8945` and
  `--https=8443 → 127.0.0.1:8946`. Idempotence comes from comparing `tailscale serve status
  --json` with the desired mappings and only applying the difference *(verify the JSON shape
  against the box's Tailscale version; newer versions also have `serve get-config`/`set-config`,
  which would make this fully declarative)*.

### `caddy`
- **Until step 7 (D3 = B behaviour):** assert that the apt package is from Cloudsmith, held, and
  that the running binary has `rate_limit`. Nothing is *installed* in this mode, only verified,
  which is how the playbook takes over the live box without touching the working Caddy.
- **From step 7 (D3 = A):** create the `caddy` system user; install `/deploy/bin/caddy` to
  `/usr/local/bin/caddy`; install `caddy.service`, a copy of upstream's unit with
  `AmbientCapabilities=CAP_NET_BIND_SERVICE` and `ExecStartPre=/usr/local/sbin/otj-render-origin-cert`
  (§7); remove the apt package and the Cloudsmith repo.
- Both modes: the Caddyfile goes to `/etc/caddy/Caddyfile`; `/var/log/caddy/` and `access.log`
  are owned by `caddy:caddy` **explicitly** (the runbook's 2026-08-22 failure); `caddy validate`
  runs with `become_user: caddy`; the handler **restarts** rather than reloads when the binary
  changed (a reload keeps the old binary in memory), and reloads when only the Caddyfile changed.

### `app`
All `become_user: otjapp` with `XDG_RUNTIME_DIR` set.
- ECR login, then `podman pull <repo>:<sha>` (already done by `otj-converge`; the task just
  confirms it).
- Quadlets from templates: `hours-api.container` and `admin-api.container`, with `Image=` set to
  the SHA, `PublishPort=127.0.0.1:…` (a **literal in the template** with a comment pointing at
  AGENTS.md, not a variable anyone could widen), `EnvironmentFile=%t/otj/<svc>.env`,
  `ExecStartPre=%h/.local/bin/otj-render-env <svc>` (§7).
- `/home/otjapp/.local/bin/otj-render-env` from the role's files.
- Handlers: `daemon-reload` (user scope), then restart the unit whose Quadlet changed.
- **Migration only (§10, step 6):** `~/otj-hours-api.env` and `~/otj-admin-api.env` set to
  `state: absent`, gated by a variable that is only turned on once the Parameter Store path has
  been proven.

### `verify`
§4.4. Runs on every apply and is skipped in `--check` mode, where nothing was restarted.

---

## 7. Secrets

**Rule: Ansible never reads, templates or prints a secret value.** It installs the *mechanism*,
and the value is fetched when the unit starts, straight from Parameter Store into tmpfs. That's
why `--diff` output (which can end up in GitHub Actions logs through SSM) can't leak anything:
the secret never appears in a file Ansible manages.

| Parameter (`SecureString` unless noted) | Read by | Written to (tmpfs, 0600) |
|---|---|---|
| `/otj/prod/mongo-uri` | hours-api, admin-api | `/run/user/<uid>/otj/<svc>.env` |
| `/otj/prod/anthropic-api-key` | hours-api | same |
| `/otj/prod/admin-allowed-logins` (`String`) | admin-api | same |
| `/otj/prod/origin-cert-key` | Caddy | `/run/caddy/origin-{cert,key}.pem` (0640 `root:caddy`) |
| `/otj/prod/credential-identity-seed` | hours-api, **only once PR #43 lands**. Carry it over **byte for byte**, because the identity key is pinned in the app bundle. | same as hours-api |
| `/otj/prod/tailscale-authkey` | `tailscale` role, only on a rebuild (§11) | not written; passed to `tailscale up` with `no_log` |

`otj-render-env <svc>` is about 20 lines of bash: `aws ssm get-parameters --with-decryption`
for that service's list of names, write `KEY=value` lines to the tmpfs file with `umask 077`,
exit non-zero if any parameter is missing. A missing secret then fails the unit at start with a
clear message, rather than the app booting and failing on its first request.

**CDK changes** (in `OtjServicesStack`, an in-place change to the IAM policy that doesn't touch
the instance; the change-set check in `deploy/prod/README.md` still applies):
`ssm:GetParameters` on `arn:…:parameter/otj/prod/*`, plus `kms:Decrypt` on the `aws/ssm` key
with an `kms:ViaService = ssm.eu-west-2.amazonaws.com` condition.

**Seeding** (once, and the only time values are handled): from the current env files, over one
`SendCommand` issued from a `workflow_dispatch` job (§9.2), piping each value straight into
`aws ssm put-parameter --type SecureString --value file:///dev/stdin`. Nothing is echoed.

**Rotation:** `aws ssm put-parameter --overwrite …`, then the *restart unit* ops workflow (§9.2).
A restart re-renders the file, and nothing else is needed.

---

## 8. CI: checks on every PR

New `.github/workflows/pr.yml`, with the existing Java checks kept.

### 8.1 Static
| Check | Catches |
|---|---|
| `ansible-lint` (production profile) | Non-idempotent patterns, missing `no_log` on obvious secrets, deprecated modules |
| `ansible-playbook --syntax-check` | Broken YAML and undefined roles |
| `shellcheck deploy/bin/* deploy/ansible/roles/*/files/*.sh` | The bash parts |
| `caddy validate` with the **CI-built** Caddy binary against `deploy/Caddyfile` (using a throwaway cert) | "unknown directive `rate_limit`" and every other Caddyfile error, before they reach the box |
| `podman-system-generator --dryrun` against the rendered Quadlets | Quadlet syntax errors, which today only show up as a unit that silently doesn't exist |

### 8.2 Integration tests
Unchanged here except for one fix: add `maven-failsafe-plugin` so `*IT` runs under `mvn verify`
instead of from a hand-kept `-Dtest=` list, which today is skipped entirely in CI.

### 8.3 The box rehearsal (the one that matters)

GitHub's `ubuntu-24.04` runner is a VM with systemd and the same OS as the box. So the job
**applies the real playbook to the runner itself**:

1. Build the image from the PR and load it into a local `otjapp` user's Podman storage.
2. `ansible-playbook site.yml -e @group_vars/ci.yml --skip-tags tailnet,aws`. `ci.yml` points
   `otj-render-env` at a fixture file of dummy values instead of Parameter Store, uses a local
   Mongo container for `MONGO_URI`, and a self-signed certificate for Caddy.
3. **Apply it a second time and fail if anything changed.** This is the idempotence check, the
   single most useful test for a playbook.
4. Check behaviour through Caddy on `https://localhost` with `--resolve`: `/health` is 200;
   11 `POST /auth/session` in quick succession returns a **429 with `Retry-After`** on the 11th;
   `:8945` and `:8946` are **not** reachable on the runner's non-loopback address.
5. `verify` has already run inside step 2, so the wildcard-listener check is covered.

This would have caught the missing-`curl` healthcheck, the root-owned access log and a
wrong-order 443 bind. It costs only runner minutes. *(Verify: linger and rootless Podman work on
the hosted runner; Podman is preinstalled there, and the fallback is a `ubuntu:24.04` container
with systemd, which is fiddlier.)*

---

## 9. CD: deploy on merge, and ops without a shell

### 9.1 `deploy.yml` (replaces `ci-cd.yml`)

`concurrency: deploy-production` (no overlapping deploys), `environment: production`.

1. Checks (the same jobs as §8, reused).
2. Build and push `otj-hours-api:<sha>`, skipped if it already exists (as today).
3. `cdk deploy OtjServicesStack`, with the change-set guard: fail if the instance or EIP would be
   replaced.
4. `ssm send-command … otj-converge <sha>`, sending full output to a CloudWatch log group
   (`--cloud-watch-output-config`), because `get-command-invocation` cuts output off at 24 KB
   and an Ansible run is longer than that. The job prints the **PLAY RECAP** plus any failed
   task.
5. Smoke test: `https://otj-services.com/health` returns 200 with a `cf-ray` header.
6. `ssm put-parameter /otj/prod/image-tag <sha>` records what is live (used by §11 and the
   rollback workflow).

**The deploy role's permissions don't change**, apart from `ssm:PutParameter` on that one
parameter.

### 9.2 Ops workflows (`workflow_dispatch`, buttons in the Actions tab)

Each one sends a **fixed** command through SSM, so there are no free-text shell inputs.

| Workflow | Input | Does |
|---|---|---|
| `rollback` | `sha` | `otj-converge <sha>` + smoke. The SHA must exist in ECR. Rolls back the app **and** its box config together. |
| `converge-check` | — | `otj-converge <live sha> --check`, and posts the diff to the job summary |
| `restart` | `unit` ∈ {hours-api, admin-api, caddy} | `systemctl restart`, then `verify` |
| `logs` | `unit`, `lines` ≤ 500 | `journalctl -u <unit> -n <lines>` into the job log. **App logs don't contain credentials (`ServerHooks` enforces it), but the job log is visible to anyone with repo read access. Keep the repo private, or drop this workflow.** |
| `seed-secret` | `name` from a fixed list | One-time seeding from the old env file (§7). Deleted after §10 step 6. |

### 9.3 Nightly drift check

A scheduled `converge-check`. If the diff isn't empty, the job fails and GitHub emails you. Any
change made in an emergency SSM session shows up within a day, and the fix is to put it in the
playbook, not to repeat the manual change.

SSM sessions stay available for emergencies. Turn on **Session Manager logging to CloudWatch**
(a setting on the account, applied with CDK) so any session is at least recorded.

---

## 10. Migrating the live box

The box is adopted, not rebuilt. Each step is its own PR, and each is checked in `--check`
mode before it is applied.

| # | Step | How it is applied | Exit check |
|---|---|---|---|
| 1 | Repo work: roles written to match **today's** box exactly (tailnet hostname `hours-api`, serve on 8443/8444, apt Caddy **verified only, not managed**, env files still in `~`, Quadlets matching the current templates) | PR, with §8 green | Rehearsal passes twice with no changes |
| 2 | **The last manual step, done without a shell:** a `workflow_dispatch` job runs one `SendCommand` that does `apt install ansible-core` and installs `otj-converge` | Actions button | `otj-converge --version` in the job output |
| 3 | `converge-check` against the live box | Actions button | Read the diff. Every line is either an intended change or a playbook bug. Fix the playbook and repeat **until the diff is empty or intended**. |
| 4 | First real apply: `otj-converge <live sha>` | Actions button | `verify` passes; public and tailnet checks from `deploy/prod/README.md` "Verifying" |
| 5 | `deploy.yml` replaces `ci-cd.yml` (§9.1) | Merge | Two ordinary merges deploy through Ansible |
| 6 | Secrets: IAM (§7), seed the parameters, switch the Quadlets to `render-env`, then after two good deploys remove the old env files | Three PRs | `ls ~otjapp/*.env` is empty; the app restarts cleanly |
| 7 | Caddy from CI (D3 = A): the new role mode, and the origin pair from Parameter Store | PR, check first | `verify`'s module check; the edge rate-limit test from the runbook |
| 8 | Nightly drift check on; Session Manager logging on; `push-file.sh` and `deploy/prod/` deleted | PR | A deliberate manual change on the box is reported the next morning |

**Rollback at any step:** the old files stay where they are until the step that removes them. For
steps 1–5, rolling back means going back to `ci-cd.yml` and `deploy.sh`, which are untouched until
step 5 merges.

---

## 11. Rebuilding from nothing

Once §10 is done, the box's whole configuration is in the image, so a new box needs only:

- **CDK user data** (on a *new* instance only; see R2): install `ansible-core`, `awscli` v2 and
  `podman`, fetch `otj-converge` from the image recorded in `/otj/prod/image-tag`, run it.
- **A Tailscale auth key** in `/otj/prod/tailscale-authkey`. Use an **OAuth client secret**
  scoped to `auth_keys` with tag `tag:otj`, which doesn't expire, rather than an ordinary auth key
  (those expire after at most 90 days, so a rebuild months later would fail at the worst moment).
- **Before** the rebuild, remove the old `hours-api` node in the Tailscale admin console, so the
  new one gets the same MagicDNS name instead of `hours-api-1`.
- The Elastic IP moves to the new instance, so **the Atlas allowlist and the Cloudflare record
  don't change**.

Rehearse it once: launch a throwaway instance from the same definition with the `tailnet` tag
skipped and no EIP, confirm `verify` passes, and terminate it (about $0.05). After a successful
rehearsal, the deliberately stale security-group description in `otj-services-stack.ts` is no
longer protecting anything irreplaceable and can finally be corrected.

---

## 12. Cost

| Item | Change |
|---|---|
| Parameter Store `SecureString`, standard tier, AWS-managed key | $0 |
| CloudWatch Logs for SSM command output and Session Manager logs (well under 1 GB/month) | < $0.50 |
| GitHub Actions minutes for the rehearsal (~5 min per PR, plus nightly) | $0 within the free allowance for private repos, at this volume |
| **Total** | **~$0.50/month on top of today's ~$23** |

---

## 13. Out of scope, and follow-ups

Each of these becomes a role or a small PR once this plan is done:

- **Cloudflare Tunnel** (D4): a `cloudflared` role, the tunnel token in Parameter Store, and a
  DNS change. It removes the Origin CA pair, the security group's Cloudflare list and the 443
  clash.
- **Observability** (your tailnet dashboard requirement): a `fluent_bit` role (journald →
  CloudWatch Logs) and a `grafana` role (a Quadlet on `127.0.0.1:3000`, `tailscale serve
  --https=8445`, auth proxy on `Tailscale-User-Login`, users from `admin-allowed-logins`).
- **Terraform for Cloudflare and Tailscale:** DNS, SSL mode, the WAF rule, Tailscale ACLs and tags.
  These are the last settings still changed through dashboards.
- **The `tailscale` branch:** self-hosters could use the same roles with a `selfhost` inventory
  instead of `deploy/bootstrap.sh`. Optional.

---

## 14. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **The first apply does something unexpected to the live box** | §10 step 3: check mode until the diff is understood. Step 1 deliberately matches the current state, so the first apply should change almost nothing. |
| R2 | **Adding user data to the existing instance.** A `UserData` change on `AWS::EC2::Instance` is an update that needs a **stop/start**, meaning a reboot of production, and cloud-init wouldn't run it anyway since it only runs on first boot. | Don't. User data goes only on a *new* instance (§11). The CDK guard (§9.1) catches this if it happens by accident. |
| R3 | **Secrets leak into GitHub logs through SSM output** | Ansible never handles secret values (§7); `no_log` on the Tailscale key task; `ansible-lint` flags obvious cases; the `logs` workflow note in §9.2 |
| R4 | **`otj-converge` breaks itself.** A bad version is installed by the playbook, and the next deploy can't run. | The playbook installs the new script **last** (after `verify`), so a bad script only affects the *next* run. Recovery is the `rollback` workflow, whose first step runs a known-good inline copy through `SendCommand`. |
| R5 | **Ansible is new to you** | §2; roles kept small and plain (builtin modules only, no third-party collections); every non-obvious task carries the runbook's reasoning as a comment |
| R6 | **Rehearsal and box differ** (runner image drift, AWS-only tasks skipped) | The nightly drift check on the real box covers what the rehearsal can't |
| R7 | **Unattended-upgrades or `apt upgrade` replaces something the playbook owns** | With D3 = A, Caddy is no longer an apt package. The next converge (every deploy, and nightly in check mode) catches any other drift. |

---

## 15. What gets deleted, and docs to update

**Deleted by the end:** `deploy/prod/` (`deploy.sh`, both templates, `push-file.sh`, and the
Caddyfile, which moves to `deploy/Caddyfile`), `.github/workflows/ci-cd.yml`, the env files and
hand-installed origin pair on the box, the apt Caddy and Cloudsmith repo.

| Doc | Change |
|---|---|
| `AGENTS.md` | Directory structure (`deploy/ansible/`); "Build, run, test" (`mvn verify`, `ansible-lint`, the rehearsal); Conventions: *"don't change the box by hand; change the playbook"* |
| `deploy/prod/README.md` → `deploy/ansible/README.md` | Rewritten around roles and workflows. The edge/rate-limit reasoning moves across as-is, since the Caddyfile hasn't changed. |
| `deployment-checklist.md` | §6 becomes "run the bootstrap workflow"; delete the manual steps |
| `aws/README.md` | Parameter Store IAM, `/otj/prod/image-tag`, Session Manager logging |
| `staging-to-master-cutover.md` | Unaffected. Still waiting on its own steps 7 and 8. |
