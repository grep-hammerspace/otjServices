# Ansible deploy checklist

The work, in order, that takes the AWS box from hand-configured to deployed by Ansible. The *why*
is in `ansible-migration-plan.md`. This file covers *what to do and when*. Section numbers (§) point
at the plan. Each phase names the plan's §10 step it carries out.

**Who does what.** Items marked 🧑 are yours: settings, a laptop command, a button, or a check that
needs your judgement or your devices. The rest is repo work that comes to you as a PR.

**Three choices made after the plan's first draft** (the plan has been updated to match):

- **`ansible-core` is installed from an SSM session** (phase 3), not from a bootstrap workflow. It's
  a one-time step and a workflow to do it isn't worth writing.
- **Secrets are entered from your laptop** (phase 5), with fresh values from Atlas and Anthropic,
  not extracted from the box through a `seed-secret` workflow. That doubles as a rotation, so the
  values that have sat in `~otjapp/*.env` since August are retired.
- **The Cloudflare origin cert stays on disk.** It is never in Parameter Store and there's no
  `otj-origin-cert.service`. HAProxy reads a root-only file that you assemble once at the cutover
  (phase 7). Ansible checks it with `stat` and `openssl x509 -checkend`, and never copies it or reads
  its contents, because `--diff` on a file holding the key would print the key into the deploy
  output. A rebuilt box (§11) gets a newly issued cert from the dashboard, which is free and takes a
  minute, so there's nothing to back up.

**Rollback** through phase 4 is doing nothing. `ci-cd.yml` and `deploy.sh` stay as they are until
phase 4 swaps them.

---

## Phase 0 — Settle the plan

- [x] 🧑 Close **D4**: Cloudflare Tunnel is *later*, as its own plan (§13).
- [x] Update `ansible-migration-plan.md` for the three choices above, plus the IAM gaps (§9.1).
      Its status is now *agreed*.
- [ ] Merge `plan/deployment-uplift` into `master`.

## Phase 1 — Inventory the live box (read-only) 🧑

Phase 2's roles must describe **today's** box exactly, or the first `converge-check` diff is noise.
These commands change nothing, and they print names, modes and versions but **no secret values**.
Env files are listed by key name only.

```bash
aws ssm start-session --target <instance-id> --region eu-west-2
sudo -i
```

```bash
U=otjapp; UID_=$(id -u $U); ASU="sudo -u $U XDG_RUNTIME_DIR=/run/user/$UID_"
set -x
# OS, packages, tools
lsb_release -ds; uname -r; df -h /; free -m
podman --version; aws --version; tailscale version; python3 --version
apt-cache policy caddy ansible-core haproxy | grep -E '^[a-z]|Installed|Candidate'
apt-mark showhold
ls /etc/apt/sources.list.d/
cat /etc/apt/apt.conf.d/20auto-upgrades; grep -E '^\s*Unattended-Upgrade::Automatic-Reboot' /etc/apt/apt.conf.d/50unattended-upgrades
# otjapp
id $U; loginctl show-user $U -p Linger
ls -la /home/$U /home/$U/otj-deploy /home/$U/.config/containers/systemd
stat -c '%n %U:%G %a' /home/$U/*.env
for f in /home/$U/*.env; do echo "$f:"; cut -d= -f1 "$f"; done   # key NAMES only
cat /home/$U/.config/containers/systemd/*.container
sha256sum /home/$U/otj-deploy/*
$ASU systemctl --user list-units --all 'hours-api*' 'admin-api*' --no-pager
$ASU podman ps --format '{{.Names}} {{.Image}} {{.Status}}'
# Tailscale
tailscale status --json | jq '{BackendState, Self: .Self.DNSName, Version}'
tailscale serve status --json
# Edge
systemctl is-enabled caddy; systemctl is-active caddy
readlink -f /proc/$(pidof caddy)/exe; $(readlink -f /proc/$(pidof caddy)/exe) list-modules | grep rate_limit
stat -c '%n %U:%G %a' /etc/caddy /etc/caddy/* /var/log/caddy /var/log/caddy/*
openssl x509 -in /etc/caddy/origin-cert.pem -noout -subject -enddate
sha256sum /etc/caddy/Caddyfile
# Listeners, sshd, journald, cron
ss -Hltnp
systemctl is-enabled ssh.socket ssh.service; systemctl is-active ssh.socket ssh.service
cat /etc/systemd/journald.conf.d/*.conf 2>/dev/null; journalctl --disk-usage
crontab -l; crontab -u $U -l
set +x
```

- [ ] Skim the output before sharing it. You shouldn't find a secret, but you're the last check.
- [ ] Paste it into the session writing phase 2.

## Phase 2 — Repo work: the playbook, matching today (plan §10 step 1)

PRs to review and merge:

- [ ] **Step-1 PR:** `deploy/ansible/` (roles `base`, `otjapp`, `tailscale`, `edge` in Caddy
      verify-only mode, `app`, `verify`), `deploy/bin/otj-converge`, `COPY deploy/ /deploy/` in the
      Dockerfile, and `pr.yml` (lint, syntax check, shellcheck, the Quadlet dry-run and the
      **rehearsal applied twice**). The Quadlets still point at `~otjapp/otj-*.env`.
      - [ ] First, a spike that proves rootless Podman and linger work on the hosted runner. If
            they don't, the rehearsal needs the systemd-container fallback, so find out early.
      - [ ] The rehearsal is green on the second apply with **zero changes**.
- [ ] **Ops-workflows PR:** `converge-check`, `converge` (apply a SHA) and `rollback`. They need to
      be on `master` before their buttons appear. Merging them is safe because none of them run on
      push. No workflow prints app or edge logs (§9.2).
- [ ] **IAM PR** (`aws/lib/`):
  - `github-oidc-stack.ts`: trust `repo:…:environment:production` next to `ref:refs/heads/master`.
    Otherwise any job that names the environment fails at AssumeRole. Also add read access to the
    converge output log group, and `ssm:PutParameter` on `/otj/prod/image-tag`.
  - `otj-services-stack.ts`: let the instance role write to that log group, so
    `--cloud-watch-output-config` works. This is an in-place policy change; check the change set
    anyway (`deploy/prod/README.md`).

🧑 Once these are ready:

- [ ] Create the **`production` environment**: Settings → Environments → New, with deployment
      branches limited to `master`.
- [ ] Review the IAM PR's `github-oidc-stack.ts` diff, then from your laptop:
      `cd aws && npx cdk diff GithubOidcStack && npx cdk deploy GithubOidcStack`. CI can't update
      the role it signs in with.
- [ ] Merge the IAM PR. CI deploys the `OtjServicesStack` half.
- [ ] Add the `pr.yml` checks, rehearsal included, as **required status checks** on `master`.
- [ ] Note the step-1 merge commit's SHA. `ci-cd.yml` builds and deploys that image as usual. It's
      the first image that contains `/deploy`.

## Phase 3 — Install Ansible on the box (§10 step 2) 🧑

From an SSM session, as root. `<sha>` is the step-1 merge commit's SHA, and `<registry>` is the ECR
registry host from the `EcrRepositoryUri` output.

```bash
apt-get update && apt-get install -y ansible-core
ansible --version | head -1

IMG=<registry>/otj-hours-api:<sha>
sudo -u otjapp -i bash -c "
  aws ecr get-login-password --region eu-west-2 | podman login -u AWS --password-stdin ${IMG%%/*}
  podman pull $IMG && cid=\$(podman create $IMG) &&
  podman cp \$cid:/deploy/bin/otj-converge /tmp/otj-converge && podman rm \$cid"
install -m 0755 -o root -g root /tmp/otj-converge /usr/local/sbin/otj-converge && rm /tmp/otj-converge
otj-converge --version
```

- [ ] `otj-converge --version` prints. From here on the playbook manages the script.

## Phase 4 — Adopt the box (§10 steps 3–5)

- [ ] 🧑 **Actions → `converge-check`**, which runs `otj-converge <live sha> --check`. Read the diff
      in the job summary. Each line is either an intended change or a playbook bug. Report the bugs.
      A PR fixes them. **Repeat until the diff is empty or every line is intended.**
- [ ] 🧑 **Actions → `converge`** with the live SHA. This is the first real apply.
- [ ] 🧑 Verify:
  - [ ] The job's PLAY RECAP shows `failed=0`, and the `verify` role ran.
  - [ ] Public path: `curl -sI https://otj-services.com/health` returns 200 with a `cf-ray` header.
  - [ ] Tailnet, from your phone or laptop: `https://hours-api.<tailnet>.ts.net:8444/health` is 200,
        and `GET :8443/admin/invites` works as an allowed login.
  - [ ] The origin is still unreachable directly: the three timeouts in `deploy/prod/README.md`,
        "Verifying".
  - [ ] Log in from the mobile app and load pending activities.
- [ ] Merge the **`deploy.yml` PR**, which replaces `ci-cd.yml` (§9.1).
- [ ] 🧑 Watch the next **two ordinary merges** deploy through Ansible: green, with the PLAY RECAP in
      the job and `/health` 200 afterwards.
- [ ] 🧑 Try **`rollback`** once, to the previous SHA and forward again, while nothing depends on it.

**From here on, merges deploy through Ansible.**

## Phase 5 — Secrets into Parameter Store (§10 step 6)

- [ ] **IAM PR:** the instance role gets `ssm:GetParameters` on `/otj/prod/*` and `kms:Decrypt` on
      `aws/ssm` through `ssm.eu-west-2.amazonaws.com` (§7). CI deploys it.
- [ ] 🧑 Create **fresh** values: a new Atlas database user or password for `otjdb`, and a new
      Anthropic key. Keep the old ones working until the last checkbox in this phase.
- [ ] 🧑 From your laptop. `read -s` keeps the values out of your shell history and off the screen:

  ```bash
  put() { read -rsp "$1: " v; echo; printf %s "$v" | aws ssm put-parameter --region eu-west-2 \
            --name "$1" --type "$2" --value file:///dev/stdin --overwrite >/dev/null && echo ok; }
  put /otj/prod/mongo-uri            SecureString
  put /otj/prod/anthropic-api-key    SecureString
  put /otj/prod/admin-allowed-logins String
  ```

  `/otj/prod/credential-identity-seed` is only needed once PR #43 lands (it's open). When it does,
  copy it **byte for byte** from the box, because the mobile app pins the identity key.
- [ ] Merge the **render-env PR**: the Quadlets switch to `otj-render-env` and `%t/otj/<svc>.env`,
      and the old files stay in place for now.
- [ ] 🧑 Two good deploys on the new path, with the mobile app still working.
- [ ] Merge the **cleanup PR**, which removes `~otjapp/otj-*.env` (`state: absent`).
- [ ] 🧑 Confirm in the deploy output that the files are gone, then **revoke the old Atlas password
      and the old Anthropic key**.

## Phase 6 — Logs to Grafana Cloud (§10 step 7)

- [ ] 🧑 Create a Grafana Cloud stack in an **EU or UK region**.
- [ ] 🧑 The five single-user rules (§4.5): you're the only member; you sign in with a passkey or
      hardware key; the `otj-alloy-prod` policy has **`logs:write` only**, for this stack; there are no
      public dashboards or snapshots; there are no other tokens.
- [ ] 🧑 `put /otj/prod/grafana-cloud-logs-token SecureString` (the helper from phase 5).
- [ ] Merge the **observability PR** (the `observability` role, `LogDriver=journald`, the journald
      drop-in). Check mode first.
- [ ] 🧑 Lines appear for `service="edge"`, `service="hours-api"` and `service="admin-api"`. Edge
      lines show **truncated** IPs. A private browser window on the stack URL asks for a login.
- [ ] 🧑 Build the dashboard (§4.5), and check `podman stats` shows room for Alloy.

## Phase 7 — Caddy → HAProxy (§10 step 8), off-peak

- [ ] 🧑 Assemble the origin pair for HAProxy, once, as root over SSM. It stays on disk (see the top
      of this file):

  ```bash
  install -d -m 0700 -o root -g root /etc/haproxy/certs
  cat /etc/caddy/origin-cert.pem /etc/caddy/origin-key.pem \
    | install -m 0600 -o root -g root /dev/stdin /etc/haproxy/certs/origin.pem
  openssl x509 -in /etc/haproxy/certs/origin.pem -noout -subject -enddate
  ```

- [ ] Merge the **HAProxy PR** (`edge_proxy: haproxy`). `converge-check` first, then apply it
      off-peak. Cloudflare returns 5xx for a few seconds during the swap.
- [ ] 🧑 Verify: `verify` passes; `/health` returns 200 from outside; with the WAF rule paused, the
      11th paced `POST /auth/session` returns **429 with `Retry-After`**, and from a different network
      the first request returns 401, not 429 (`deploy/prod/README.md`, "Verifying"). **Re-enable the
      WAF rule.** In Grafana, the edge panel goes from `proxy="caddy"` to `proxy="haproxy"`.

## Phase 8 — Cleanup (§10 step 9)

- [ ] Merge the **cleanup PR**: nightly `converge-check` on, Session Manager logging on, apt Caddy
      and the Cloudsmith repo removed, `/etc/caddy/` deleted (the origin pair now lives in
      `/etc/haproxy/certs/`), `deploy/prod/` deleted, and the docs updated (§15).
- [ ] 🧑 Make a deliberate, harmless manual change on the box, such as touching a managed file.
      Confirm the nightly check reports it the next morning, then let the next converge put it back.

## Phase 9 — Afterwards (optional, §11 and §13)

- [ ] Rehearse a rebuild on a throwaway instance (§11). Issue a new origin cert for it, rather than
      copying the live one.
- [ ] Authenticated Origin Pulls. The origin currently accepts any Cloudflare zone, not only yours.
