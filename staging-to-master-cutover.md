# Cutover runbook — `staging` → `master`

One-time. This is the merge that takes the multi-user auth rollout (steps 02–08), the tailnet-only
admin API, and the public Caddy edge to production together.

Delete this file once it has been run. Ongoing operations live in `deploy/prod/README.md`.

---

## Status as of 2026-08-16 — the merge has already happened

**Phase 2 is done and most of Phase 1 with it.** Verified against the live box
(`i-06dd830c8fbde9685`) and `origin/master`, not assumed:

| Step | State |
|---|---|
| 1.2 admin env file | **done** — `~otjapp/otj-admin-api.env`, mode 600 |
| 1.3 deploy script + templates | **done** — all three files in `~otjapp/otj-deploy/` |
| 1.5 install Caddy | **not done** — no `caddy` binary, no `/etc/caddy` |
| 1.6 point DNS at the box | **done** — apex A record, proxied (orange cloud) |
| 2 merge `staging` → `master` | **done** — PR #21, `origin/master` at `583ddff` |
| — app deployed | **done** — both containers running `583ddff`, `/health` 200 on 8945 and 8946 |
| 3 bring up the public edge | **not done** — security group still has zero ingress rules |
| 4 mint the first invite | **not done** |

So what is left is Phase 1.5, Phase 3 and Phase 4 — the edge itself. **Read Phase 3
from `deploy/prod/README.md` ("Provisioning the edge") rather than from this file**, which is
kept only for the reasoning behind the ordering and for Phase 4.

Two consequences of the merge already being live, both of which make the rest *easier* than
this document originally assumed — see the next section.

---

## What this merge changes

| | Before (`master`) | After |
|---|---|---|
| Reaching the main API | tailnet only, `tailscale serve --https=443` | **public**, `https://otj-services.com` via Caddy; tailnet moves to `:8444` |
| Identity | `Tailscale-User-Login` header, trusted | bearer tokens, bcrypt users, invite-gated signup |
| Admin API | does not exist | `admin-api` container, tailnet only on `:8443` |
| Containers on the box | 1 (`hours-api`) | 2 (`hours-api`, `admin-api`) |
| Inbound ports | none | 443, from Cloudflare ranges only |

**Every existing user starts from zero.** `master` has no user model, so there is nothing to migrate
— there are no accounts to preserve. Nobody can use the API until you mint an invite code (Phase 4).

**No database migration is needed.** Every index is created in code at construction —
`UserRepository:38`, `SessionRepository:41-42`, `InviteCodeRepository:58`, `LlmQuotaService:68,71`.
The Atlas user's `readWrite` role covers `createIndex`.

---

## The one thing you must not do — **no longer applies**

This was the load-bearing constraint of the original plan, and it is now satisfied. It is kept
here so that nobody re-derives it from the old `master` and re-imposes the ordering.

> **Do not let Caddy serve traffic before the new image is running.**
>
> `master`'s app derives identity from a client-supplied header. `TailscaleIdentityHelper` reads
> `Tailscale-User-Login` off the request and trusts it, safe only because nothing but loopback can
> reach 8945. **Caddy does not strip that header.** If Caddy proxies public traffic while the old
> image is running, anyone can send `Tailscale-User-Login: <anything>` and be treated as that user.
>
> CI makes this a live race, because it opens the door before it swaps the app:
>
> ```
> cdk deploy  →  security group opens 80/443     ← public traffic possible from here
>    ↓
> docker build + push to ECR                     ← several minutes
>    ↓
> ssm send-command → deploy.sh                   ← new image finally running
> ```
>
> The mitigation is ordering: install Caddy in Phase 1 but **leave the Caddyfile off the box** until
> Phase 3.

**Why it is now moot.** The race existed only in the window between opening the ports and
replacing the old image. That window is closed: the merged image is already deployed and serving,
and on today's `master` `Tailscale-User-Login` is read by `AdminIdentityFilter` alone — the admin
API on 8946, which Caddy never proxies. The main API on 8945 authenticates with bearer tokens via
`AuthenticationFilter`. Confirm before relying on this:

```bash
git grep -n 'Tailscale-User-Login' origin/master -- src/main    # AdminIdentityFilter only
```

So Caddy and the Caddyfile can now go on in one pass; there is no need to install Caddy config-less
and wait. The remaining ordering constraint is a much duller one — free port 443 from `tailscaled`
*before* installing the Caddyfile, per `deploy/prod/README.md`.

**What has not changed:** the admin API's safety still rests entirely on nothing but loopback and
`tailscale serve` reaching 8946. The Caddyfile proxies only to 8945. Do not add an 8946 upstream.

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

### 1.1 Merge PR #39 into `master` — **outstanding**

Retargeted from `staging` to `master` on 2026-08-16: `staging` was merged into `master` by PR #21
and is now the older of the two, so the original base would have produced a diff against a branch
nothing deploys from. `staging` is still an ancestor of `master`, so the retarget is clean.

PR #39 also now carries the container healthcheck fix — see 1.3.

### 1.2 Create the admin API's environment file — **done**

**Before 1.3.** As `otjapp`, mode 600:

```
MONGO_URI=<the same SRV string as ~/otj-hours-api.env>
ADMIN_ALLOWED_LOGINS=you@example.com
```

No `ANTHROPIC_API_KEY` — the admin Dagger graph never constructs the LLM client.

An unset or wrong `ADMIN_ALLOWED_LOGINS` denies **everyone**. That is the intended failure mode, but
it reads exactly like a broken deploy, and it locks you out of minting the first invite.

### 1.3 Push the deploy script and templates — **done, but see the healthcheck note**

```bash
push_file deploy/prod/deploy.sh                     /home/otjapp/otj-deploy/deploy.sh otjapp 755
push_file deploy/prod/admin-api.container.template  /home/otjapp/otj-deploy/admin-api.container.template
push_file deploy/prod/hours-api.container.template  /home/otjapp/otj-deploy/hours-api.container.template
```

**Both containers currently report `unhealthy` while serving `/health` 200 on 8945 and 8946.** The
templates' `HealthCmd=curl -f ...` runs *inside* the container, and the runtime image
(`eclipse-temurin:25-jre`, Ubuntu 26.04) ships no HTTP client at all — no curl, no wget, no nc. The
probe exits 127 with `curl: not found` on every tick; the failing streak was 162 when this was
found. Nothing was actually wrong with the app.

It stayed hidden because `deploy.sh` health-checks from the *host*, where curl exists — so deploys
go green and only `podman ps` shows it. PR #39 fixes it by installing curl in the runtime stage of
`docker/otjService.Dockerfile`. It needs a rebuild and redeploy, not just a template push.

Until that lands, **do not use `podman ps` health as a cutover signal** — curl from the host, or
`podman inspect <name> --format '{{json .State.Health}}'` to see the real reason.

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

~~**Leave `/etc/caddy/Caddyfile` as the packaged default.** That is what keeps you safe during the
merge window.~~ **No longer required** — the merge window has closed, so you can go straight on to
Phase 3 and install the Caddyfile in the same session. See "The one thing you must not do" above.

### 1.6 Point DNS at the box — **done**

Cloudflare dashboard, zone `otj-services.com` (already delegated to `dan.ns.cloudflare.com` /
`ollie.ns.cloudflare.com`):

```
Type: A    Name: @    Content: 18.169.107.161    Proxy status: Proxied (orange cloud)
```

`18.169.107.161` is the current `ElasticIp` output of `OtjServicesStack`. Re-read it rather than
trusting this line if the stack has been recreated since 2026-08-16.

**The API lives on the apex, not on `api.`** — a subdomain was the original plan and bought
nothing, so it was dropped on 2026-08-16 in favour of the record that already existed.

**Proxied, not DNS-only** — the deliberate choice being that the Elastic IP is never published.
That decision changes three other things, all of which are already reflected in this PR and
explained in `deploy/prod/README.md`: rate-limit keys move to `{client_ip}` with a
`trusted_proxies` list, the security group narrows to Cloudflare's ranges on 443 (port 80 is not
opened at all), and TLS on the origin is a **Cloudflare Origin CA certificate rather than Let's
Encrypt** — ACME cannot reach an origin that only admits Cloudflare.

`dig` will show Cloudflare's addresses, not the Elastic IP. That is the proxy working:

```bash
dig +short A otj-services.com @1.1.1.1     # 104.21.x.x / 172.67.x.x — expected
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

## Phase 2 — merge — **done (PR #21)**

Merge `staging` → `master`. `origin/master` is at `583ddff`, and that image is what both containers
are running.

CI runs `mvn -B test` → `cdk deploy OtjServicesStack` → build and push the image → `deploy.sh` over
SSM → poll for health.

**Correction to the original text: this merge did *not* open 80 and 443.** The ingress rules live in
PR #39, which was still unmerged, so `cdk deploy` ran against a stack that opens nothing. The
security group still has zero ingress rules today:

```bash
aws ec2 describe-security-groups --region eu-west-2 \
  --filters Name=tag:aws:cloudformation:stack-name,Values=OtjServicesStack \
  --query 'SecurityGroups[].IpPermissions'                       # [] as of 2026-08-16
```

The ports therefore open when **PR #39** merges, not when this phase ran. That is the moment the box
becomes reachable from Cloudflare's edge, so have Caddy and its certificate (1.5, 3.2) in place
first — otherwise 443 is open onto a host with nothing listening for the duration.

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

### 3.2 Install the Cloudflare Origin CA certificate

Full instructions in `deploy/prod/README.md` step 4. In short: Cloudflare → SSL/TLS → Origin
Server → Create Certificate, then put the pair on the box as root, and set the zone's SSL/TLS mode
to **Full (strict)**.

```bash
# /etc/caddy/origin-cert.pem   644 root:caddy
# /etc/caddy/origin-key.pem    640 root:caddy   ← the key is shown once, at creation
sudo -u caddy test -r /etc/caddy/origin-key.pem && echo "caddy can read the key"
```

There is **no ACME on this origin** — Let's Encrypt cannot reach a box that only admits Cloudflare.
If you find yourself watching for a certificate to be issued, something is wrong.

### 3.3 Install the Caddyfile

```bash
push_file deploy/prod/Caddyfile /etc/caddy/Caddyfile root 644
verify_files                                        # unrelated to Caddy, but cheap reassurance
```

Then on the box:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
sudo journalctl -u caddy -f
```

Validate **before** reloading. A reload with a broken config leaves the old config running; a
restart with one leaves nothing serving. `validate` also opens the certificate and key, so a
missing or unreadable pair fails here rather than at reload.

The log should say `skipping automatic certificate management because one or more matching
certificates are already loaded`, and `automatic HTTP->HTTPS redirects are disabled`.

If `validate` says `rate_limit is not a registered directive`, the plugin is missing — go back to
1.5. Do not "fix" it by deleting the rate limit blocks.

### 3.4 Verify

```bash
curl -sI https://otj-services.com/health | head -1            # 200
curl -sI https://otj-services.com/health | grep -i '^cf-ray'  # came via Cloudflare
```

Port 80 is not open and Caddy does not bind it — Cloudflare terminates the visitor's HTTP at its
edge, so there is no origin redirect to test.

Rate limiting, and the header the mobile client depends on:

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
done; echo

curl -si -X POST https://otj-services.com/auth/session \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}' | grep -i retry-after
```

Expect `401` turning to `429` at the eleventh request, and a `Retry-After` on the 429. If the header
is missing, delete the `handle_errors` block from the Caddyfile — it matters more to the client than
the custom message does to anyone.

**Then send one request from a different network** — a phone off wifi will do. It must come back
`401`, not `429`. A `429` on the first request from a fresh IP means Caddy is keying the limiter on
Cloudflare's edge address instead of the real client, i.e. `trusted_proxies` is not matching, and
every user in the world is sharing one bucket. Fix that before letting anyone on.

Finally, confirm the origin is reachable *only* through Cloudflare. **From off the tailnet**,
against the public IP:

```bash
curl --max-time 5 -k "https://$ELASTIC_IP/health"          # the one people forget
curl --max-time 5 "http://$ELASTIC_IP:8945/health"
curl --max-time 5 "http://$ELASTIC_IP:8946/admin/invites"
```

All three must time out. A connection refused means the packet reached the host; a `403` would mean
the port is reachable and only the app is stopping it. If the first one answers, the security group
still has an `0.0.0.0/0` rule on 443 and the proxy is trivially bypassable — which would defeat the
reason for choosing orange cloud in the first place.

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
curl -s -X POST https://otj-services.com/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-XXXX-XXXX","username":"asad","password":"...","learnerId":"..."}'
# -> 201 {"token":"..."}
```

---

## After the cutover

- **Update the mobile client.** `otj-mobile/.env.example` still reads
  `EXPO_PUBLIC_API_URL=https://example.ts.net`; it becomes `https://otj-services.com`.
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
