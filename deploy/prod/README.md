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
| Public — `api.otj-services.com` | 443 (and 80 → redirect) | Caddy → `127.0.0.1:8945` | yes, by Caddy |
| Tailnet — main API | 8444 | `tailscale serve` → `127.0.0.1:8945` | **no** |
| Tailnet — admin API | 8443 | `tailscale serve` → `127.0.0.1:8946` | n/a |

Shell access to the box itself is SSM Session Manager. There is no SSH port and no SSH key.

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
listener is moved (step 3) before the Caddyfile is installed (step 4).

The alternative — binding Caddy to the instance's private IP so it never touches the tailscale
interface — is worse: the instance is built from `MachineImage.fromSsmParameter`, so a new
Ubuntu AMI can replace it on an ordinary `cdk deploy`, and the private IP would change with it.

## Provisioning the edge

Once, as root over SSM. The box is assumed already provisioned per the app-side steps (podman,
`otjapp`, lingering, Quadlets, Tailscale joined).

### 1. Point DNS at the box

The domain is registered with **Cloudflare Registrar**, which requires Cloudflare's own
nameservers — so there is no Route 53 hosted zone and no CDK `ARecord`. In the Cloudflare
dashboard, for zone `otj-services.com`:

```
Type: A    Name: api    Content: <ElasticIp from the OtjServicesStack outputs>
Proxy status: DNS only (grey cloud)
```

**Grey cloud, not orange.** Orange-cloud proxying changes both certificate issuance and what
`{remote_host}` means in the Caddyfile; if you want it, do it as its own deliberate change (see
"Putting Cloudflare in front" below) rather than as a side effect of setting up DNS.

Wait for it to resolve before installing Caddy — the ACME HTTP-01 challenge needs the name to
already point here, and failed validations count against Let's Encrypt rate limits:

```
dig +short A api.otj-services.com @1.1.1.1
```

### 2. Install Caddy, with the rate limiting module

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

# Rate limiting is NOT in a stock build. This replaces the binary with a custom one.
sudo caddy add-package github.com/mholt/caddy-ratelimit
caddy list-modules | grep rate_limit          # must print http.handlers.rate_limit
```

Then stop apt from ever putting the stock binary back:

```bash
sudo apt-mark hold caddy
```

**This is not optional.** `apt upgrade` would overwrite the custom binary with a stock one, and
a stock binary cannot parse our Caddyfile at all — `rate_limit` is an unknown directive, so
Caddy would fail to start and the public API would go down. It fails closed rather than serving
unlimited traffic, which is the right way round, but it is still an outage. Held package plus
`sudo caddy upgrade` for updates keeps the plugin set across upgrades.

### 3. Move the main API's tailnet listener off 443

**Before installing the Caddyfile, not after.** Until this runs, `tailscaled` holds 443 and the
Caddyfile below cannot bind it — see the port note above.

```bash
tailscale serve --bg --https=8444 http://127.0.0.1:8945    # main API, was 443
tailscale serve --bg --https=8443 http://127.0.0.1:8946    # admin API, unchanged
tailscale serve status
```

If a `--https=443` mapping is still there from the old setup, remove it:
`tailscale serve --https=443 off`. Confirm 443 is actually free before continuing — adding the
8444 mapping does not by itself drop the old one:

```bash
ss -lntp | grep ':443 ' || echo "443 free"
```

### 4. Install the Caddyfile

```bash
sudo install -m 644 -o root -g root Caddyfile /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile      # parse check before you restart anything
sudo systemctl reload caddy
```

Certificates are obtained automatically on first start and stored under `/var/lib/caddy`. The
`caddy` user already holds `CAP_NET_BIND_SERVICE` from the packaged unit, so 80/443 bind without
running as root and without touching `net.ipv4.ip_unprivileged_port_start`.

If Caddy fails to start here, check for `address already in use` before anything else — it means
step 3 did not actually free 443.

## Verifying

```bash
# TLS and the public path
curl -sI https://api.otj-services.com/health | head -1        # 200
curl -sI http://api.otj-services.com/health | head -1         # 308 to https

# The 429 body AND the header the mobile client depends on. The Caddyfile overrides the 429
# body with a custom message; this is the check that the override did not drop Retry-After.
for i in $(seq 1 12); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://api.otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
done; echo
curl -si -X POST https://api.otj-services.com/auth/session \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y}' | grep -i retry-after
```

The first loop should turn from `401` to `429` at the eleventh request. If `Retry-After` is
missing from the 429, delete the `handle_errors` block from the Caddyfile — the header is worth
more to the client than the custom message is to anyone.

Then confirm nothing else got exposed:

```bash
# From OFF the tailnet, against the public IP. Both must fail to connect outright —
# a 403 would mean the port is reachable and only the app is stopping it.
curl --max-time 5 http://<ElasticIp>:8946/admin/invites
curl --max-time 5 http://<ElasticIp>:8945/health
```

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

**None of this stops a distributed attack.** Traffic from thousands of sources still arrives at
the NIC and still costs TLS handshakes. AWS Shield Standard (automatic, free) covers the L3/L4
volumetric case. For L7, the answer is a CDN in front — below.

## Putting Cloudflare in front, later

If the origin ever gets genuinely targeted, flip the `api` record to orange-cloud. It is a
toggle rather than a migration precisely because DNS already lives at Cloudflare. Three things
have to change together:

1. Every `key {remote_host}` in the Caddyfile becomes `key {client_ip}`, and the site needs a
   `trusted_proxies` block naming Cloudflare's ranges — otherwise every request appears to come
   from a handful of Cloudflare IPs and the rate limiter throttles the entire user base as one.
2. The security group narrows from `0.0.0.0/0` to Cloudflare's published ranges. Without this,
   anyone who finds the Elastic IP just skips the proxy.
3. Re-verify certificate renewal. Caddy keeps serving the origin certificate behind the proxy;
   HTTP-01 through an orange-cloud record works, but confirm a renewal actually completes rather
   than discovering it 60 days later.
