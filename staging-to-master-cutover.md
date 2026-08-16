# Cutover runbook — `staging` → `master`

One-time. This is the merge that takes the multi-user auth rollout (steps 02–08), the tailnet-only
admin API, and the public Caddy edge to production together.

Delete this file once it has been run. Ongoing operations live in `deploy/prod/README.md`.

---

## What this merge changes

| | Before (`master`) | After |
|---|---|---|
| Reaching the main API | tailnet only, `tailscale serve --https=443` | **public**, `https://api.otj-services.com` via Caddy; tailnet moves to `:8444` |
| Identity | `Tailscale-User-Login` header, trusted | bearer tokens, bcrypt users, invite-gated signup |
| Admin API | does not exist | `admin-api` container, tailnet only on `:8443` |
| Containers on the box | 1 (`hours-api`) | 2 (`hours-api`, `admin-api`) |
| Inbound ports | none | 80, 443 |

**Every existing user starts from zero.** `master` has no user model, so there is nothing to migrate
— there are no accounts to preserve. Nobody can use the API until you mint an invite code (Phase 4).

**No database migration is needed.** Every index is created in code at construction —
`UserRepository:38`, `SessionRepository:41-42`, `InviteCodeRepository:58`, `LlmQuotaService:68,71`.
The Atlas user's `readWrite` role covers `createIndex`.

---

## The one thing you must not do

**Do not let Caddy serve traffic before the new image is running.**

`master`'s app derives identity from a client-supplied header. `TailscaleIdentityHelper` reads
`Tailscale-User-Login` off the request and trusts it, safe only because nothing but loopback can
reach 8945. **Caddy does not strip that header.** If Caddy proxies public traffic while the old
image is running, anyone can send `Tailscale-User-Login: <anything>` and be treated as that user.

CI makes this a live race, because it opens the door before it swaps the app:

```
cdk deploy  →  security group opens 80/443     ← public traffic possible from here
   ↓
docker build + push to ECR                     ← several minutes
   ↓
ssm send-command → deploy.sh                   ← new image finally running
```

The mitigation is ordering: install Caddy in Phase 1 but **leave the Caddyfile off the box** until
Phase 3. A Caddy with no site config for your domain serves nothing, so the open ports lead nowhere
until you are ready.

---

## Setup

```bash
cd ~/Projects/personal/java/otjServices && nix-shell     # provides aws, cdk, node, jq
aws sts get-caller-identity                              # must succeed
tailscale status                                         # needed for Phases 3–4
```

Note your **tailnet login** — the email Tailscale knows you by, from `tailscale status` or the
Tailscale admin console. It goes in `ADMIN_ALLOWED_LOGINS` and is *not* your app username.

```bash
export AWS_REGION=eu-west-2
INSTANCE_ID=$(aws cloudformation describe-stacks --stack-name OtjServicesStack \
  --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)
ELASTIC_IP=$(aws cloudformation describe-stacks --stack-name OtjServicesStack \
  --query "Stacks[0].Outputs[?OutputKey=='ElasticIp'].OutputValue" --output text)
echo "$INSTANCE_ID $ELASTIC_IP"
```

### Getting a shell on the box

No SSH port, no SSH key. Access is SSM Session Manager, gated by IAM:

```bash
aws ssm start-session --target "$INSTANCE_ID" --region eu-west-2
```

You land as `ssm-user`:

```bash
sudo -i                    # root: apt, /etc/caddy, system units
sudo -u otjapp -i          # app: ~otjapp files, podman, systemctl --user
```

**`systemctl --user` needs `XDG_RUNTIME_DIR`**, and a plain `sudo -u otjapp -i` does not always set
it. If you get `Failed to connect to bus`:

```bash
sudo -u otjapp XDG_RUNTIME_DIR=/run/user/$(id -u otjapp) systemctl --user status hours-api.service
```

Lingering is enabled for `otjapp`, so the user manager runs with nobody logged in.

### Getting files onto the box

SSM has no `scp`. Use the helpers:

```bash
source deploy/prod/push-file.sh     # push_file, ssm_wait, ssm_run, verify_files
```

`push_file` base64-encodes locally so the content survives JSON encoding and two layers of shell
quoting — `deploy.sh` alone contains `$HOME`, `${IMAGE_URI}` and `"${1:?usage}"`, all of which a
plain text transfer would mangle. If you paste by hand instead, use a **quoted** heredoc marker
(`<<'ENDOFFILE'`) for the same reason.

---

## Phase 1 — stage everything (nothing changes behaviour yet)

Every step is inert. `deploy.sh` and the templates execute only when CI triggers them, and Caddy has
no config for your domain until Phase 3.

### 1.1 Merge PR #39 into `staging`

### 1.2 Create the admin API's environment file

**Before 1.3.** As `otjapp`, mode 600:

```
MONGO_URI=<the same SRV string as ~/otj-hours-api.env>
ADMIN_ALLOWED_LOGINS=you@example.com
```

No `ANTHROPIC_API_KEY` — the admin Dagger graph never constructs the LLM client.

An unset or wrong `ADMIN_ALLOWED_LOGINS` denies **everyone**. That is the intended failure mode, but
it reads exactly like a broken deploy, and it locks you out of minting the first invite.

### 1.3 Push the deploy script and templates

```bash
push_file deploy/prod/deploy.sh                     /home/otjapp/otj-deploy/deploy.sh otjapp 755
push_file deploy/prod/admin-api.container.template  /home/otjapp/otj-deploy/admin-api.container.template
push_file deploy/prod/hours-api.container.template  /home/otjapp/otj-deploy/hours-api.container.template
```

`hours-api.container.template` is functionally unchanged, but the box's copy was installed by
pasting and carries ~1.7 KB of trailing whitespace on 19 of its 20 lines (389 bytes in the repo,
2116 on the box). systemd strips trailing whitespace from unit values so it has never broken
anything — push the clean copy anyway, so all three files match the branch you are about to merge.

> **Order matters.** `deploy.sh` health-checks every service in `SERVICES` and exits non-zero if any
> one fails; CI fails the whole job on that. Installing the admin template without
> `~/otj-admin-api.env` from 1.2 turns a good `hours-api` deploy into a red build.

### 1.4 Verify the files actually match

`push_file` prints a command id as soon as SSM *queues* the command — that is not evidence anything
worked. Check by checksum:

```bash
verify_files
```

```
  MATCH   deploy.sh
  MATCH   hours-api.container.template
  MATCH   admin-api.container.template
```

Anything other than three `MATCH` lines, stop and fix it before merging. This is the check that
would have caught the whitespace drift above, and it is the reason issue #40 exists.

### 1.5 Install Caddy and the rate limiting module — but not the Caddyfile

As root on the box:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

sudo caddy add-package github.com/mholt/caddy-ratelimit
caddy list-modules | grep rate_limit        # must print http.handlers.rate_limit
sudo apt-mark hold caddy
```

`apt-mark hold` is not optional. `apt upgrade` would restore the stock binary, and a stock binary
cannot parse our Caddyfile at all — the public API would fail to start. Use `sudo caddy upgrade` for
updates; it preserves the module set.

**Leave `/etc/caddy/Caddyfile` as the packaged default.** That is what keeps you safe during the
merge window.

### 1.6 Point DNS at the box

Cloudflare dashboard, zone `otj-services.com`:

```
Type: A    Name: api    Content: <ELASTIC_IP>    Proxy status: DNS only (grey cloud)
```

Grey cloud, not orange — orange changes both certificate issuance and what `{remote_host}` means in
the Caddyfile, and belongs in its own deliberate change.

Confirm it resolves before Phase 3. A failed ACME challenge burns Let's Encrypt rate-limit budget,
and those limits are per-domain-per-week:

```bash
dig +short A api.otj-services.com @1.1.1.1
```

### 1.7 Run the test suite locally

CI runs `mvn -B test` on push to `master` and will not deploy if it fails. Integration tests are
skipped by Surefire's defaults and must be named:

```bash
mvn -B clean test
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true \
  mvn -B test -Dtest='CucumberIT,UserRepositoryIT,ActivityLogRepositoryIT,SessionTokenServiceIT,InviteCodeRepositoryIT,SessionRepositoryIT,LlmQuotaServiceIT'
```

---

## Phase 2 — merge

Merge `staging` → `master`.

CI runs `mvn -B test` → `cdk deploy OtjServicesStack` (this opens 80 and 443) → build and push the
image → `deploy.sh` over SSM → poll for health. Watch it to green. Nothing is publicly reachable
during this window because Caddy still has no site config for your domain.

Confirm both containers came up:

```bash
ssm_run "sudo -u otjapp XDG_RUNTIME_DIR=/run/user/\$(id -u otjapp) systemctl --user is-active hours-api.service admin-api.service"
ssm_run "curl -sf http://127.0.0.1:8945/health && curl -sf http://127.0.0.1:8946/health && echo BOTH_OK"
```

If `admin-api` failed, it is almost certainly `~/otj-admin-api.env`:

```bash
ssm_run "sudo -u otjapp XDG_RUNTIME_DIR=/run/user/\$(id -u otjapp) journalctl --user -u admin-api.service --no-pager -n 50"
```

---

## Phase 3 — bring up the public edge

Only now, with the new image confirmed running.

### 3.1 Move the main API's tailnet listener off 443

```bash
tailscale serve status                                     # what is configured now
tailscale serve --https=443 off                            # older CLIs: `tailscale serve reset`
tailscale serve --bg --https=8444 http://127.0.0.1:8945    # main API
tailscale serve --bg --https=8443 http://127.0.0.1:8946    # admin API
tailscale serve status                                     # 8444 and 8443, no 443
```

Anything pointing at the tailnet name on `:443` breaks here and needs `:8444`. Confirm 443 is free
before the next step — `sudo ss -lntp | grep :443` should show nothing.

### 3.2 Install the Caddyfile

```bash
push_file deploy/prod/Caddyfile /etc/caddy/Caddyfile root 644
verify_files                                        # unrelated to Caddy, but cheap reassurance
```

Then on the box:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
sudo journalctl -u caddy -f          # watch the certificate get issued
```

Validate **before** reloading. A reload with a broken config leaves the old config running; a
restart with one leaves nothing serving.

If `validate` says `rate_limit is not a registered directive`, the plugin is missing — go back to
1.5. Do not "fix" it by deleting the rate limit blocks.

### 3.3 Verify

```bash
curl -sI https://api.otj-services.com/health | head -1     # 200
curl -sI http://api.otj-services.com/health  | head -1     # 308 to https
```

Rate limiting, and the header the mobile client depends on:

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://api.otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
done; echo

curl -si -X POST https://api.otj-services.com/auth/session \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}' | grep -i retry-after
```

Expect `401` turning to `429` at the eleventh request, and a `Retry-After` on the 429. If the header
is missing, delete the `handle_errors` block from the Caddyfile — it matters more to the client than
the custom message does to anyone.

Finally, confirm nothing else got exposed. **From off the tailnet**, against the public IP:

```bash
curl --max-time 5 "http://$ELASTIC_IP:8945/health"
curl --max-time 5 "http://$ELASTIC_IP:8946/admin/invites"
```

Both must fail to connect outright. A `403` would mean the port is reachable and only the app is
stopping it — stop and fix that.

---

## Phase 4 — mint the first invite

From a tailnet device whose login is in `ADMIN_ALLOWED_LOGINS`:

```bash
curl -s -X POST https://hours-api.<tailnet>.ts.net:8443/admin/invites \
  -H 'Content-Type: application/json' \
  -d '{"note":"first code","expiresInDays":7}'
# -> 201 {"code":"OTJ-XXXX-XXXX", ...}
```

Confirm the gate holds — from a tailnet device **not** on the allowlist, the same call must return
`403`. If it succeeds, the allowlist is not being applied; stop before minting anything real.

Sign up against the public endpoint:

```bash
curl -s -X POST https://api.otj-services.com/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-XXXX-XXXX","username":"asad","password":"...","learnerId":"..."}'
# -> 201 {"token":"..."}
```

---

## After the cutover

- **Update the mobile client.** `otj-mobile/.env.example` still reads
  `EXPO_PUBLIC_API_URL=https://example.ts.net`; it becomes `https://api.otj-services.com`.
- **Your rollback floor has moved.** With the two-service `deploy.sh` installed you cannot roll back
  past the commit that added the admin API. `master`'s old `start.sh` has no `APP_ROLE` handling —
  it always runs the main API — so an older image in the `admin-api` container would listen on 8945
  while the Quadlet health-checks 8946, never go healthy, and fail the whole deploy.
- **Delete this file.**

### Rolling back an ordinary deploy

SHA tags in ECR are immutable and the last 20 are retained:

```bash
aws ssm send-command --instance-ids "$INSTANCE_ID" --region eu-west-2 \
  --document-name AWS-RunShellScript \
  --parameters '{"commands":["sudo -u otjapp -i /home/otjapp/otj-deploy/deploy.sh 378849626815.dkr.ecr.eu-west-2.amazonaws.com/otj-hours-api:<good-sha>"]}'
```

### Taking the public endpoint down fast

```bash
sudo systemctl stop caddy
```

The tailnet paths on `:8444` and `:8443` keep working, so you keep admin access and a way to test.
