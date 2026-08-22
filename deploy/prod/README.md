# The production box

Everything in this directory is installed **by hand** onto the EC2 host and is inert until it
is. Nothing here is applied by CI — CI only builds an image, pushes it to ECR, and runs
`deploy.sh` (which already lives on the box) via SSM.

| File | Installed to | Owner |
|---|---|---|
| `deploy.sh` | `~otjapp/otj-deploy/deploy.sh` | `otjapp` |
| `hours-api.container.template` | `~otjapp/otj-deploy/` | `otjapp` |
| `admin-api.container.template` | `~otjapp/otj-deploy/` | `otjapp` |
| `Caddyfile` | `/etc/caddy/Caddyfile` | `root` |

**Install order matters.** `deploy.sh` health-checks every service in its `SERVICES` list and
exits non-zero if any one fails, and CI fails the whole job on that exit code. So a template
must never reach the box before the env file it needs: dropping in
`admin-api.container.template` without `~otjapp/otj-admin-api.env` turns a perfectly good
hours-api deploy into a red build.

## How traffic reaches the app

Three ways in, and only three:

| Path | Port on the host | Reaches | Rate limited |
|---|---|---|---|
| Public — `otj-services.com` | 443, **from Cloudflare ranges only** | Cloudflare edge → Caddy → `127.0.0.1:8945` | yes — a Cloudflare WAF rule *and* Caddy |
| Tailnet — main API | 8444 | `tailscale serve` → `127.0.0.1:8945` | **no** |
| Tailnet — admin API | 8443 | `tailscale serve` → `127.0.0.1:8946` | n/a |

Shell access to the box itself is SSM Session Manager. There is no SSH port and no SSH key.

**The origin sits behind the Cloudflare proxy, and port 80 is closed.** `otj-services.com` is an
orange-cloud record: it resolves to Cloudflare, and the Elastic IP is never published. Cloudflare
terminates the visitor's TLS at its edge and speaks HTTPS to this box, which presents a Cloudflare
Origin CA certificate — there is no ACME on the origin and nothing to serve on 80.

That arrangement is three settings that only work as a set. Change one and you have to change all
three:

| | Where | If it drifts |
|---|---|---|
| `{client_ip}` rate-limit keys | `Caddyfile` | `{remote_host}` is the Cloudflare edge — every user shares one bucket and all get throttled together |
| `trusted_proxies` = CF ranges | `Caddyfile` | `{client_ip}` silently falls back to the edge IP; same collapse, no error |
| 443 allowed from CF ranges only | `aws/lib/otj-services-stack.ts` | anyone who finds the Elastic IP bypasses Cloudflare *and* can forge `CF-Connecting-IP` to spoof the rate-limit key |

The Elastic IP not being in DNS is not the same as it being secret — certificate transparency
logs, DNS history and plain scanning all turn up origins. The security group is what actually
enforces this, not the proxy.

The tailnet path to the main API bypasses Caddy entirely, so it is unmetered. That is fine —
it is an operator path, reachable only by devices on the tailnet — but it means "I tested it
over Tailscale and the rate limit didn't fire" is expected behaviour, not a bug.

**The main API's tailnet port is 8444, not 443.** Caddy owns 443 on this host now, and the move
is required rather than tidy-minded. `tailscaled` binds 443 on the tailnet interface
specifically, not on the wildcard:

```
LISTEN  100.120.113.30:443              users:(("tailscaled",...))
LISTEN  [fd7a:115c:a1e0::d637:711f]:443 users:(("tailscaled",...))
```

Caddy binds the wildcard `:443`, which collides with an existing bind on the same port unless
both sockets opt into `SO_REUSEPORT` — so leaving the old `--https=443` mapping in place risks
Caddy failing to start on a box where the API is otherwise healthy. This is why the tailnet
listener is moved (step 3) before the Caddyfile is installed (step 5).

The alternative — binding Caddy to the instance's private IP so it never touches the tailscale
interface — is worse: the instance is built from `MachineImage.fromSsmParameter`, so a new
Ubuntu AMI can replace it on an ordinary `cdk deploy`, and the private IP would change with it.

## The two rate limits, and why there are two

Traffic is metered twice on the way in, at layers with very different costs.

| | Where it runs | Scope | Limit | On trip |
|---|---|---|---|---|
| Cloudflare WAF rate limiting rule | Cloudflare's edge | `/auth/signup`, `/auth/session` | **3 requests / 10 s per IP** | block for 10 s |
| Caddy `auth_burst` | this box | `/auth/signup`, `/auth/session` | 10 / min per IP | 429 |
| Caddy `auth_daily` | this box | `/auth/signup`, `/auth/session` | 250 / day per IP | 429 |
| Caddy `api_burst` | this box | everything else | 120 / min per IP | 429 |

**The edge rule is the one that is free to enforce.** Caddy's limiter runs on a `t3.small`: every
request it rejects has still cost a TCP handshake, a TLS handshake and a Go handler. A request
Cloudflare drops costs this box nothing at all. That is the whole reason for the duplication — the
edge rule is the load shedder, and Caddy is the backstop.

The two are not redundant, because they bite on different shapes of traffic:

- **3 / 10 s** kills a burst but permits 18/minute sustained, which is *above* Caddy's 10/minute.
- **10 / min** catches the patient attacker who paces themselves under the edge rule.

So a slow credential-stuffing run passes Cloudflare and is stopped by Caddy; a flood is stopped by
Cloudflare before it reaches Caddy. Neither alone covers both.

Caddy's limits are also what protects the origin if someone finds the Elastic IP and the security
group is ever widened — the edge rule is not in that path at all.

**The edge block is a `429` with `Retry-After`** — the same shape Caddy's limiter sends, so the
mobile client's existing backoff handles both layers without knowing which one blocked it.
Verified against production on 2026-08-22:

```
1:401 2:401 3:401 4:429 5:429 6:429

HTTP/2 429
content-type: text/plain; charset=UTF-8
retry-after: 10
server: cloudflare
```

Worth re-checking if the rule's action is ever changed. A **Managed Challenge** in place of
**Block** would return an interactive challenge page instead, which the Expo client cannot solve
and has no handling for — it would surface as a hard error rather than "try again shortly".

**One thing to watch:**

- **3 / 10 s is tight for a shared egress IP.** On campus wifi every student is one address. Three
  sign-ins inside ten seconds anywhere on that network trips it for all of them, and the ten
  second block means it recovers quickly but will recur under any real concurrency. It is the
  right starting point for an endpoint nobody hits in a loop legitimately — but if signups cluster
  (a cohort onboarding together, a demo), this is the first thing that will misfire, and the
  symptom will be reported as "the app is broken", not as a rate limit.

## Provisioning the edge

Once, as root over SSM. The box is assumed already provisioned per the app-side steps (podman,
`otjapp`, lingering, Quadlets, Tailscale joined).

> ### Order: the box must be ready *before* PR #39 merges
>
> `.github/workflows/ci-cd.yml` runs on `push` to `master`, and its first deploy step is
> `npx cdk deploy OtjServicesStack`. The security group's Cloudflare ingress rules live in PR #39.
> **So merging #39 opens 443 by itself, within a few minutes, with no further action from you.**
>
> Steps 2–5 below all have to be done before that happens. Merging first leaves 443 open on a host
> with nothing listening on it — not a security hole (there is no service to reach) but a window
> where the public name is down, and it lasts as long as the provisioning takes.
>
> Do steps 2–5, then merge. Step 1 (DNS) is already done and can be ignored.
>
> **Never edit the security group's `GroupDescription`.** It is immutable in CloudFormation, so
> changing it replaces the whole group. The replacement changes `GroupId`, which feeds the
> instance's `SecurityGroupIds`, which CloudFormation marks `RequiresRecreation: Conditionally` —
> it decides at execution time whether to recreate the instance. It should not for a VPC instance,
> but this box is hand-provisioned and reproducible from nothing, so the safe move is not to ask
> the question. The description in `otj-services-stack.ts` is deliberately stale and carries a
> comment saying so; the accurate account of the access model lives here and in `aws/README.md`.
>
> With the description left alone, `cdk deploy` adds the ingress rules **in place**:
> `Replacement: False` on the group, and the instance's entry in the changeset drops to
> `Evaluation: Dynamic` — listed because CloudFormation cannot statically prove `GroupId` is
> unchanged, not because anything will happen to it. Confirm before any deploy that touches this
> group:
>
> ```bash
> cdk deploy OtjServicesStack --no-execute --require-approval never
> aws cloudformation describe-change-set --stack-name OtjServicesStack \
>   --change-set-name cdk-deploy-change-set --region eu-west-2 \
>   --query 'Changes[].ResourceChange.{L:LogicalResourceId,R:Replacement,D:Details}'
> aws cloudformation delete-change-set --stack-name OtjServicesStack \
>   --change-set-name cdk-deploy-change-set --region eu-west-2
> ```
>
> `Evaluation: Static` on the instance means the change is real and it may be recreated. Stop.

### 1. Point DNS at the box, and add the edge rate limit — **done**

Both of these are already in place; this section is the record of what was set, not a step to run.

The domain is registered with **Cloudflare Registrar**, which requires Cloudflare's own
nameservers — so there is no Route 53 hosted zone and no CDK `ARecord`. In the Cloudflare
dashboard, for zone `otj-services.com`:

```
Type: A    Name: @    Content: 18.169.107.161      (the OtjServicesStack ElasticIp output)
Proxy status: Proxied (orange cloud)
```

Under **Security → WAF → Rate limiting rules**, matching
`http.request.uri.path in {"/auth/signup" "/auth/session"}`, characteristic **IP**:

```
3 requests / 10 seconds  →  Block, mitigation timeout 10 seconds
```

Why only those two paths: they are the only ones reachable without a bearer token, and each costs
a full bcrypt verify — see "The two rate limits, and why there are two" above for how this layers
with Caddy's zones, and for the two things about it still worth checking.

The free plan allows a single rate limiting rule, so this one is the whole budget. If it is ever
spent elsewhere, the anonymous endpoints fall back to Caddy alone — which still holds, but at the
cost of the box's CPU rather than Cloudflare's.

Then under **SSL/TLS → Overview**, set the encryption mode to **Full (strict)**. Anything less
than "Full" makes Cloudflare talk plaintext HTTP to an origin that only listens on 443, and plain
"Full" accepts any certificate at all, including an expired or self-signed one — which throws away
the point of putting a real certificate on the origin.

**Verifying this from `dig` does not work, by design.** A proxied record resolves to Cloudflare,
so the Elastic IP is invisible:

```bash
dig +short A otj-services.com @1.1.1.1        # Cloudflare IPs (e.g. 104.21.x.x, 172.67.x.x)
```

That is the proxy working, not a misconfiguration. To confirm the origin is right, read the
record's content in the dashboard. To confirm the *path* end to end, use the checks under
"Verifying" below — they are what actually prove traffic reaches Caddy.

If you ever see the Elastic IP itself come back from `dig`, the record has been switched to grey
cloud, and the security group (Cloudflare ranges only) will now be blocking every visitor.

### 2. Install Caddy, with the rate limiting module

> **Ubuntu ships its own `caddy`, and it is the wrong one.** `noble/universe` carries
> `caddy 2.6.2-6ubuntu0.24.04.3`. `caddy add-package` did not exist until Caddy 2.7, so if apt
> resolves the package from Ubuntu instead of Cloudsmith, the install *succeeds*, the service unit
> is created, everything looks correct — and then `add-package` fails with
> `unknown command "add-package" for "caddy"`. Nothing before that point tells you.
>
> This is easy to hit, because adding a source list and having apt actually *use* it are two
> different things: if `apt update` runs before the list file is in place, or the file lands
> mangled, apt falls back to Ubuntu's copy without comment. Verify the repo is live before
> installing — the check is in the block below and it costs nothing.

> **Do not paste these as multi-line commands over SSM.** Caddy's published instructions use
> `curl … \` + `| sudo tee …`, and this session mangles the continuation: the fragment after the
> backslash arrives as its own line, so the shell reports `Syntax error: "|" unexpected` — or,
> worse, silently concatenates the downloaded content onto the command line and writes nothing
> where you meant. That is how you end up with no `caddy-stable.list`, a corrupt keyring, and an
> apt that quietly falls back to Ubuntu's 2.6.2. The forms below are single-line and pipe-free
> for exactly that reason. Run them **one at a time**.

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
```

```bash
sudo curl -1sLf -o /tmp/caddy.key 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key'
```

```bash
sudo gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg /tmp/caddy.key
```

```bash
sudo sh -c "echo 'deb [signed-by=/usr/share/keyrings/caddy-stable-archive-keyring.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main' > /etc/apt/sources.list.d/caddy-stable.list"
```

Only the `deb` line is needed; the upstream file also carries a `deb-src` line, which is for
building from source and is not used here.

**Check both files landed, then that apt is really reading the repo.** `apt update` prints one
line per source; a run with no `dl.cloudsmith.io` in it means the repo is not active and the next
command will install Ubuntu's 2.6.2:

```bash
ls -l /usr/share/keyrings/caddy-stable-archive-keyring.gpg   # ~1-2 KB binary, not 0 bytes
cat /etc/apt/sources.list.d/caddy-stable.list                # the cloudsmith deb line
sudo apt update 2>&1 | grep cloudsmith                       # must print something
apt-cache policy caddy                                       # Candidate 2.7+, origin cloudsmith
```

If the keyring is 0 bytes or `gpg: no valid OpenPGP data found` appears at `apt update`, the
dearmor step got mangled — delete it and redo it rather than layering another attempt on top.

Only once `apt-cache policy` names Cloudsmith as the candidate's origin:

```bash
sudo apt install -y caddy
caddy version                                      # 2.7 or newer

# Rate limiting is NOT in a stock build. This replaces the binary with a custom one.
sudo caddy add-package github.com/mholt/caddy-ratelimit
caddy list-modules | grep rate_limit               # must print http.handlers.rate_limit
```

**If Ubuntu's 2.6.2 is already installed**, fix the repo as above and then upgrade over it —
`sudo apt install -y caddy` is enough once Cloudsmith is the candidate, and it keeps the `caddy`
user and `/etc/caddy`. If apt will not move to it, `sudo apt purge -y caddy` and reinstall.

Then stop apt from ever putting the stock binary back — **after** `list-modules` has confirmed the
plugin is in, never before. Holding early just pins whatever wrong version you have:

```bash
sudo apt-mark hold caddy
apt-mark showhold | grep caddy      # confirm it took
```

**Now restart Caddy, while its config is still the harmless packaged default.**

```bash
sudo systemctl restart caddy
sudo caddy list-modules --packages        # confirms the *running* build has the module
```

`add-package` swaps the binary on disk but says so explicitly — *"please restart any running Caddy
instances"* — because the process already in memory is the old one. That matters at step 5:
`systemctl reload caddy` does not re-exec, it hands the new config to the **running** process over
the admin API. Reload into a pre-`add-package` process and it rejects the config with an unknown
module error, which reads exactly like a broken Caddyfile and sends you back to debug a file that
is perfectly fine.

Restarting here rather than at step 5 keeps the two failures separate: if the binary swap is bad
you find out now, against a default config, instead of during the cutover.

**This is not optional.** `apt upgrade` would overwrite the custom binary with a stock one, and
a stock binary cannot parse our Caddyfile at all — `rate_limit` is an unknown directive, so
Caddy would fail to start and the public API would go down. It fails closed rather than serving
unlimited traffic, which is the right way round, but it is still an outage. Held package plus
`sudo caddy upgrade` for updates keeps the plugin set across upgrades.

### 3. Move the main API's tailnet listener off 443

**Before installing the Caddyfile, not after.** Until this runs, `tailscaled` holds 443 and the
Caddyfile in step 5 cannot bind it — see the port note above.

**Only the main API needs moving.** The admin API is on 8443 and always has been. In
`tailscale serve status` the main API appears on the **bare tailnet name with no port**, because
that *is* 443 — it is easy to see `:8443` in the output, read it as "already moved", and then lose
step 5 to `address already in use` on a box where everything looks healthy:

```
https://hours-api.<tailnet>.ts.net              -> 127.0.0.1:8945    # this is :443, move it
https://hours-api.<tailnet>.ts.net:8443         -> 127.0.0.1:8946    # admin, leave alone
```

**These need `sudo`.** Without it `tailscale serve` fails with `Access denied: serve config
denied`. To stop needing it: `sudo tailscale set --operator=$USER`, once.

```bash
sudo tailscale serve --https=443 off                            # older CLIs: `serve reset`
sudo tailscale serve --bg --https=8444 http://127.0.0.1:8945    # main API, was 443
sudo tailscale serve status                                     # 8444 and 8443, no bare name
```

Then confirm the socket is actually gone. Adding the 8444 mapping does **not** by itself drop the
443 one, and this is the check that decides whether step 5 works:

```bash
ss -lntp | grep ':443 ' || echo "443 free"
```

Anything calling the tailnet name on `:443` breaks here and needs `:8444` — including
`deployment-checklist.md`'s smoke test and any local script or client pointed at the bare name.

### 4. Issue and install the Cloudflare Origin CA certificate

Cloudflare dashboard → **SSL/TLS → Origin Server → Create Certificate**. Accept the defaults
(RSA, hostnames `otj-services.com` and `*.otj-services.com`, 15 years). You get a certificate and
a private key, and **the key is shown exactly once** — copy both before leaving the page.

This certificate is only ever presented to Cloudflare, which is the only thing the security group
lets connect. It is not publicly trusted and browsers never see it; visitors get Cloudflare's own
certificate at the edge. Its 15-year life is why there is no renewal story on this box.

Install both, as root:

```bash
sudo install -m 644 -o root -g caddy /dev/stdin /etc/caddy/origin-cert.pem <<'EOF'
-----BEGIN CERTIFICATE-----
...
EOF

sudo install -m 640 -o root -g caddy /dev/stdin /etc/caddy/origin-key.pem <<'EOF'
-----BEGIN PRIVATE KEY-----
...
EOF
```

Mode `640` and group `caddy` on the key: Caddy drops to the `caddy` user, so it must be able to
read it, and nothing else should. Check it took:

```bash
sudo -u caddy test -r /etc/caddy/origin-key.pem && echo "caddy can read the key"
openssl x509 -in /etc/caddy/origin-cert.pem -noout -subject -dates
```

### 5. Install the Caddyfile

```bash
sudo install -m 644 -o root -g root Caddyfile /etc/caddy/Caddyfile
sudo -u caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

> **Validate as `caddy`, not as root.** This is not tidiness, and getting it wrong actively breaks
> the reload rather than merely failing to catch a problem.
>
> `validate` provisions the config's modules, and the `log` block's file writer **creates
> `/var/log/caddy/access.log` when it does**. Run as root, the file is created `root:root` mode
> 600. The service then starts as `USER=caddy`, cannot open its own log file, and the reload dies
> with `open /var/log/caddy/access.log: permission denied` — an error that points at the log
> writer while the actual cause was the validation command run one line earlier.
>
> Hit on 2026-08-22. If you see that error, the fix is
> `sudo chown caddy:caddy /var/log/caddy/access.log`, then reload again.
>
> The same applies to anything else run as root against this config, including `ssm_run`, which
> executes as root on the box. Validating as `caddy` reproduces the service's own permissions and
> so tests what actually matters.

`validate` also opens the certificate and key, so a missing or unreadable pair fails here rather
than at reload. The `caddy` user already holds `CAP_NET_BIND_SERVICE` from the packaged unit, so
443 binds without running as root and without touching `net.ipv4.ip_unprivileged_port_start`.

`reload` is correct here **only because step 2 already restarted Caddy onto the `add-package`
binary**. If that restart was skipped, the running process has no `rate_limit` module and will
reject this config with an unknown-module error that looks like a Caddyfile bug. If you see one,
check `sudo caddy list-modules --packages` against the running service before touching the file.

There is no ACME step and nothing is written to `/var/lib/caddy`. On reload the log should say
`skipping automatic certificate management because one or more matching certificates are already
loaded`, and `automatic HTTP->HTTPS redirects are disabled` — if instead you see Caddy trying to
solve an ACME challenge, the `tls` line did not take and it will fail, because Let's Encrypt
cannot reach this origin.

**One warning on every reload is expected and is not a fault:**

```
stapling OCSP  error: no OCSP stapling for [cloudflare origin certificate *.otj-services.com
otj-services.com]: no URL to issuing certificate
```

Cloudflare Origin CA certificates carry no OCSP responder URL, so there is nothing for Caddy to
staple. That is fine here: the certificate is only ever presented to Cloudflare, which does not
check origin certificates for revocation that way, and revoking one is done in the Cloudflare
dashboard rather than through OCSP. There is no configuration that makes this warning go away and
none is wanted — chasing it leads to putting a publicly-issued certificate on an origin that
cannot complete an ACME challenge.

If Caddy fails to start here, check for `address already in use` before anything else — it means
step 3 did not actually free 443.

## Verifying

```bash
# The public path, end to end. `cf-ray` proves the response came through Cloudflare rather than
# from something you reached directly.
curl -sI https://otj-services.com/health | head -1            # 200
curl -sI https://otj-services.com/health | grep -i '^cf-ray'  # present
```

### The edge rule

A fast loop now tests **Cloudflare**, not Caddy — at 3 requests per 10 seconds the edge rule trips
on the fourth, long before Caddy's zone has seen enough traffic to care:

```bash
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
done; echo
```

Expect `401 401 401 429 429 429` — three through, then blocked on the fourth. Confirm the block
still carries `Retry-After`, which is what the mobile client backs off on:

```bash
curl -si -X POST https://otj-services.com/auth/session \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}' \
  | head -20                                   # status line, and any Retry-After
```

As of 2026-08-22 this returns `HTTP/2 429`, `retry-after: 10`, `server: cloudflare`. A `403` or an
HTML challenge page instead means the rule's action has been changed away from **Block**, and the
Expo client has no handling for either.

### Caddy's own limiter

**The edge rule makes this untestable from outside while it is enabled** — you cannot generate 11
requests in a minute through Cloudflare without being blocked at the fourth. To test the layer
underneath, pause the rate limiting rule in the dashboard, run the paced loop, then re-enable it:

```bash
# 11 requests, 4s apart: ~44s total, so the 11th is inside Caddy's 1m window.
for i in $(seq 1 11); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
  sleep 4
done; echo
```

The loop should turn from `401` to `429` at the eleventh request, and that `429` must carry
`Retry-After` — the Caddyfile overrides the 429 body, and this is the check that the override did
not drop the header. If it is missing, delete the `handle_errors` block from the Caddyfile; the
header is worth more to the client than the custom message is to anyone.

**Then check the limiter keys on the real client IP, not the edge.** This is the failure that looks
fine in a smoke test and takes everyone down under load — if `trusted_proxies` is wrong, every
visitor shares one bucket. Still with the WAF rule paused, from a **different network** (a phone
off wifi is enough), send one request: it must be `401`, not `429`. A `429` on the first request
from a fresh IP means Caddy is bucketing by Cloudflare's edge address and the entire user base is
sharing one counter — fix `trusted_proxies` before letting anyone on.

Re-enable the WAF rule when you are done. It is easy to leave paused, and nothing will remind you:
the site works perfectly with it off.

Then confirm the origin is not reachable except through Cloudflare:

```bash
# From anywhere that is not a Cloudflare edge and not on the tailnet. All three must TIME OUT,
# not refuse and not answer — a connection refused means the packet reached the host.
curl --max-time 5 https://<ElasticIp>/health      -k   # 443: blocked by the security group
curl --max-time 5 http://<ElasticIp>:8945/health        # app, never published
curl --max-time 5 http://<ElasticIp>:8946/admin/invites # admin API, tailnet only
```

The first one is the one people forget. If it answers, the security group still has an
`0.0.0.0/0` rule on 443 and the origin is trivially bypassable — see the three-settings table at
the top of this file.

## Why the rate limits are shaped the way they are

The cap is per source IP, and **users do not have their own IPs**. On campus wifi every student
shares one NAT'd egress address; on mobile data they share a carrier CGNAT pool. A per-IP daily
cap is therefore a *shared* budget, and a flat 250/day across the whole API would let ten
students on campus lock out everyone else before lunch.

So the Caddyfile splits it:

- **`/auth/signup`, `/auth/session`** get both a 10/minute burst limit and the 250/day cap.
  This is the surface worth protecting: there is no user identity to key on yet, and every
  request costs a full bcrypt verify — including ones naming a user that does not exist, because
  `AuthResource` verifies against `DUMMY_HASH` to keep the timing constant. The app's own
  limiter keys on the submitted username and so does nothing against an attacker who rotates
  them. On a burstable `t3.small`, sustained bcrypt drains CPU credits and then throttles the
  whole host.
- **Everything else** gets a 120/minute burst ceiling and no daily cap. It already requires a
  bearer token, and the expensive path is bounded per user by `LlmQuotaService` at 10 LLM calls
  per day. A daily per-IP cap here would add nothing except the campus outage.
- **`/health`** is uncapped. A monitor polling every 5 minutes is 288 requests/day and would
  trip a 250/day cap on its own, taking the alerting with it.

The short windows are the load-bearing part. A 1-minute window that trips recovers in a minute;
a 24-hour window that trips is a day-long outage for everyone sharing that address.

**Caddy's limiter does not stop a distributed attack**, and behind the proxy it never sees one.
Traffic from thousands of sources is absorbed at Cloudflare's edge — their L3/L4 and L7
protection, plus the rate limiting rule in step 1, which is the part of this we actually
configured. AWS Shield Standard still covers the volumetric case for anything aimed straight at
the Elastic IP, which the security group drops anyway. Caddy's job here is the narrower one:
bounding what a *legitimate-looking* client can spend of the box's CPU, and holding the line at
all if the edge is ever bypassed.

## Keeping the Cloudflare ranges current

The one maintenance task this design creates. Cloudflare's ranges are in two places that must
agree — `trusted_proxies` in `Caddyfile` and `CLOUDFLARE_IPV4`/`CLOUDFLARE_IPV6` in
`aws/lib/otj-services-stack.ts`. They were last synced **2026-08-16**.

Compare Cloudflare's published lists against what the stack actually declares — not against each
other:

```bash
# Run from the repo root. Prints any CIDR Cloudflare publishes that the stack is missing.
comm -23 \
  <(curl -s https://www.cloudflare.com/ips-v4 https://www.cloudflare.com/ips-v6 | sort) \
  <(grep -oE '"[0-9a-f:.]+/[0-9]+"' aws/lib/otj-services-stack.ts | tr -d '"' | sort)
```

No output means they agree. Anything printed goes into **both** `otj-services-stack.ts` and the
`trusted_proxies` block in `Caddyfile`, and needs a `cdk deploy` plus a `systemctl reload caddy`.

Cloudflare changes these rarely, and a new range going missing fails *quietly* rather than
loudly, which is what makes it worth an occasional check:

- Missing from the **security group** → visitors routed via that edge get connection timeouts,
  and only some of them, so it reads as a flaky network rather than a config error.
- Missing from **`trusted_proxies`** → Caddy stops believing `CF-Connecting-IP` for those
  requests and buckets them under the edge address, throttling a slice of users as one.

## Going back to a directly-reachable origin

If you ever want Cloudflare out of the path, it is the same three settings in reverse, and all
three have to move together:

1. Flip the record to grey cloud (DNS only).
2. Widen the security group to `0.0.0.0/0` on 443, and open 80 if you want ACME back.
3. Swap the Caddyfile's `tls` line back to ACME, drop `trusted_proxies`, and change every
   `key {client_ip}` to `key {remote_host}` — `{client_ip}` without a trusted proxy in front is
   just a client-supplied header, so leaving it would let anyone set their own rate-limit bucket.

Doing any one of these alone breaks the site: grey cloud without step 2 blocks every visitor,
and step 2 without step 1 exposes the origin while Cloudflare still fronts it.
