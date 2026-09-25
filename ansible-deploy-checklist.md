# Ansible deploy checklist

The work, in order, that builds the new AWS box with Ansible and brings the public API back. The
*why* is in `ansible-migration-plan.md`. This file covers *what to do and when*. The step numbers
match the plan's §10.

**Where things stand (2026-09-25).** The hand-provisioned box was destroyed when a merge replaced
the instance (plan, top). The instance now running, `i-0d937e3a7abb82ebb`, is blank Ubuntu. The
public API returns 521 and stays down, by choice, until step 5. User data in Atlas, the images in
ECR and the Elastic IP are all intact.

**Who does what.** Items marked 🧑 are yours: settings, a laptop command, a dashboard, a button, or
a check that needs your judgement or your devices. The rest is repo work that comes to you as a PR.

**Laptop commands** run from the repo root in `nix-shell`, which provides `aws`, `cdk` and the
Session Manager plugin. Check first that `aws sts get-caller-identity` works.

---

## Step 1 — Stop it happening again

- [ ] 🧑 Merge **#45**. It pins the AMI and adds `aws/stack-policy.json`. `cdk diff` against the live
      stack shows no resource changes, so the instance is untouched.
- [ ] 🧑 Set the stack policy. `cdk deploy` can't, so this is by hand, once:

  ```bash
  cd aws
  aws cloudformation set-stack-policy --stack-name OtjServicesStack --region eu-west-2 \
    --stack-policy-body file://stack-policy.json
  aws cloudformation get-stack-policy --stack-name OtjServicesStack --region eu-west-2
  ```

**Nothing else merges to `master` before #45.** Any deploy without it can replace the instance again.

## Step 2 — Repo work (PRs)

- [ ] **Runner spike:** a throwaway workflow proving rootless Podman, linger and `systemctl --user`
      work on GitHub's `ubuntu-24.04` runner. The rehearsal depends on it, and the fallback (a systemd
      container) is much more work, so find out first.
- [ ] **Playbook PR:** `deploy/ansible/` (roles `base`, `otjapp`, `tailscale`, `edge`, `app`,
      `verify`), `deploy/bin/otj-converge`, `deploy/haproxy/haproxy.cfg` ported from the Caddyfile,
      `COPY deploy/ /deploy/` in the Dockerfile, and `pr.yml` (lint, syntax check, shellcheck,
      `haproxy -c`, the Quadlet dry-run, and the **rehearsal applied twice**). Also deletes
      `deploy/prod/` and `staging-to-master-cutover.md`, after moving their reasoning into
      `deploy/ansible/README.md` and `haproxy.cfg`'s comments.
  - [ ] The rehearsal is green, and the **second apply changes nothing**.
- [ ] **Ops-workflows PR:** `converge-check`, `converge`, `rollback` and `restart` (plan §9.2).
      They have to be on `master` before their buttons appear. None of them runs on push, and no
      workflow prints app or edge logs.
- [ ] **IAM PR** (`aws/lib/`):
  - `otj-services-stack.ts`, for the instance role: `ssm:GetParameters` on `/otj/prod/*`,
    `kms:Decrypt` on `aws/ssm` through `ssm.eu-west-2.amazonaws.com`, and write access to the
    converge-output log group.
  - `github-oidc-stack.ts`, for the deploy role: trust `repo:…:environment:production`, read the
    converge-output log group, and `ssm:PutParameter` on `/otj/prod/image-tag`.

## Step 3 — Manual prep, off the box 🧑

GitHub:

- [ ] Settings → Environments → **New environment `production`**, with deployment branches limited
      to `master`.
- [ ] Review the IAM PR's `github-oidc-stack.ts` diff, then run
      `cd aws && npx cdk diff GithubOidcStack && npx cdk deploy GithubOidcStack`. CI can't update
      the role it signs in with.
- [ ] Merge the IAM PR. CI deploys the `OtjServicesStack` half.
- [ ] Settings → Branches → `master`: add the `pr.yml` checks, rehearsal included, as **required
      status checks**.

Secrets, into Parameter Store with **fresh** values. `read -s` keeps them off the screen and out of
your shell history:

```bash
put() { read -rsp "$1: " v; echo; printf %s "$v" | aws ssm put-parameter --region eu-west-2 \
          --name "$1" --type "$2" --value file:///dev/stdin --overwrite >/dev/null && echo ok; }
```

- [ ] Atlas: create a new password for the `otjdb` database user, or a new user, and check the
      Elastic IP is still on the IP access list (it hasn't changed). Then
      `put /otj/prod/mongo-uri SecureString`.
- [ ] Anthropic console: create a new key, then `put /otj/prod/anthropic-api-key SecureString`.
- [ ] `put /otj/prod/admin-allowed-logins String`, with the comma-separated tailnet logins allowed
      to mint invite codes.
- [ ] Revoke the old Atlas password and the old Anthropic key, once you've checked nothing else
      uses them (your local `.env`, for instance).
- [ ] `/otj/prod/credential-identity-seed` is only needed once **PR #43** lands. When it does, mint a
      pair with `IdentityKeyTool generate` and put the public half in `otj-mobile/.env` at the same
      time.

Tailscale:

- [ ] Admin console → Machines: **remove the offline `hours-api` node**, so the new box gets the same
      name instead of `hours-api-1`.
- [ ] Create an **OAuth client** with the `auth_keys` write scope and tag `tag:otj` (add `tag:otj` to
      `tagOwners` in the ACL first). OAuth client secrets don't expire, unlike auth keys (90 days
      at most). Then `put /otj/prod/tailscale-authkey SecureString`.

Cloudflare:

- [ ] SSL/TLS → Origin Server → **Create Certificate**: RSA, `otj-services.com` and
      `*.otj-services.com`, 15 years. **The key is shown once.** Keep the cert and the key somewhere
      private until step 4, then delete that copy.
- [ ] On the same page, **revoke the old origin certificate**. Its key was on the lost disk.

Check (lists names only; nothing is decrypted):

- [ ] `aws ssm get-parameters-by-path --path /otj/prod --region eu-west-2 --query 'Parameters[].Name'`
      lists `mongo-uri`, `anthropic-api-key`, `admin-allowed-logins` and `tailscale-authkey`.

## Step 4 — Bootstrap the box (SSM session) 🧑

The last hand change the box gets. `<sha>` is the playbook PR's merge commit, whose image
`ci-cd.yml` pushes to ECR. `<registry>` is the host part of the `EcrRepositoryUri` stack output.

```bash
aws ssm start-session --target i-0d937e3a7abb82ebb --region eu-west-2
sudo -i
```

```bash
# Packages otj-converge needs before Ansible exists
apt-get update && apt-get install -y ansible-core podman unzip curl

# AWS CLI v2, for the ECR login. The base role takes over and pins the version.
curl -fsSLo /tmp/awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip
unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscliv2.zip
aws --version

# otj-converge, from the image. From then on the playbook manages it.
IMG=<registry>/otj-hours-api:<sha>
aws ecr get-login-password --region eu-west-2 | podman login -u AWS --password-stdin "${IMG%%/*}"
podman pull "$IMG" && cid=$(podman create "$IMG")
podman cp "$cid:/deploy/bin/otj-converge" /usr/local/sbin/otj-converge
podman rm "$cid" && podman rmi "$IMG"
chown root:root /usr/local/sbin/otj-converge && chmod 0755 /usr/local/sbin/otj-converge
otj-converge --version
```

The origin certificate. Paste the cert, then the key, into one file:

```bash
install -d -m 0700 -o root -g root /etc/haproxy/certs
install -m 0600 -o root -g root /dev/stdin /etc/haproxy/certs/origin.pem <<'EOF'
-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----
-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----
EOF
openssl x509 -in /etc/haproxy/certs/origin.pem -noout -subject -enddate
```

Paste the key now, **before step 8** turns on Session Manager logging. After that, a pasted key
would be recorded in CloudWatch (plan §7 has the method to use then).

- [ ] `otj-converge --version` prints.
- [ ] The certificate shows `otj-services.com` and an end date 15 years out.
- [ ] Delete your local copy of the key.

## Step 5 — First converge: the API comes back

- [ ] 🧑 **Actions → `converge-check`** with the playbook PR's SHA. Read what it will do: on a blank
      box, that's everything. Look for anything **unexpected**, such as a port or path you don't
      recognise.
- [ ] 🧑 **Actions → `converge`** with the same SHA.
- [ ] 🧑 Verify:
  - [ ] The PLAY RECAP shows `failed=0`, and `verify` ran.
  - [ ] `curl -sI https://otj-services.com/health` returns 200 with a `cf-ray` header.
  - [ ] Tailnet, from your phone or laptop: `https://hours-api.<tailnet>.ts.net:8444/health` returns
        200, and `GET :8443/admin/invites` works as an allowed login.
  - [ ] The origin is only reachable through Cloudflare. From outside, all three of these **time out**:
        `https://<ElasticIp>/health`, `http://<ElasticIp>:8945/health` and
        `http://<ElasticIp>:8946/admin/invites`.
  - [ ] The rate limits, with the Cloudflare WAF rule paused. Eleven `POST /auth/session` requests
        4 s apart: the 11th is a **429 with `Retry-After`**. The first request from a different
        network (a phone off wifi) is a 401, not a 429. **Re-enable the WAF rule.**
  - [ ] The mobile app logs in and loads pending activities.

**The public API is back.**

## Step 6 — Merges deploy through Ansible

- [ ] Merge the **`deploy.yml` PR**, which replaces `ci-cd.yml` (plan §9.1).
- [ ] 🧑 Watch the next **two ordinary merges** deploy: green, the PLAY RECAP in the job, and
      `/health` 200 afterwards.
- [ ] 🧑 Try **`rollback`** once, to the previous SHA and forward again.

## Step 7 — Logs to Grafana Cloud

- [ ] 🧑 Create a Grafana Cloud stack in an **EU or UK region**.
- [ ] 🧑 The five single-user rules (plan §4.5): you're the only member; you sign in with a passkey or
      hardware key; the `otj-alloy-prod` access policy has **`logs:write` only**, for this stack;
      there are no public dashboards or snapshots; there are no other tokens.
- [ ] 🧑 `put /otj/prod/grafana-cloud-logs-token SecureString` (the helper from step 3).
- [ ] Merge the **observability PR** (the `observability` role, `LogDriver=journald`, the journald
      drop-in). Run `converge-check` first.
- [ ] 🧑 Lines appear for `service="edge"`, `service="hours-api"` and `service="admin-api"`. Edge
      lines show **truncated** IPs. A private browser window on the stack URL asks for a login.
- [ ] 🧑 Build the dashboard (plan §4.5), and check `podman stats` shows room for Alloy.

## Step 8 — Drift check and session logging

- [ ] Merge the PR that turns on the nightly `converge-check` and Session Manager logging (CDK).
- [ ] 🧑 Make a deliberate, harmless manual change on the box. Confirm the nightly check reports it
      the next morning, then converge to put it back.

## Afterwards (optional)

- [ ] Rehearse a rebuild on a throwaway instance (plan §11).
- [ ] CDK user data for new instances, so a rebuild needs no step 4 by hand.
- [ ] Authenticated Origin Pulls. The origin currently accepts any Cloudflare zone, not only yours.
