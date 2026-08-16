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
| Public — `otj-services.com` | 443, **from Cloudflare ranges only** | Caddy → `127.0.0.1:8945` | yes, by Caddy |
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
Type: A    Name: @    Content: <ElasticIp from the OtjServicesStack outputs>
Proxy status: Proxied (orange cloud)
```

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
sudo caddy validate --config /etc/caddy/Caddyfile      # parse check before you restart anything
sudo systemctl reload caddy
```

`validate` also opens the certificate and key, so a missing or unreadable pair fails here rather
than at reload. The `caddy` user already holds `CAP_NET_BIND_SERVICE` from the packaged unit, so
443 binds without running as root and without touching `net.ipv4.ip_unprivileged_port_start`.

There is no ACME step and nothing is written to `/var/lib/caddy`. On reload the log should say
`skipping automatic certificate management because one or more matching certificates are already
loaded` — if instead you see Caddy trying to solve an ACME challenge, the `tls` line did not take
and it will fail, because Let's Encrypt cannot reach this origin.

If Caddy fails to start here, check for `address already in use` before anything else — it means
step 3 did not actually free 443.

## Verifying

```bash
# The public path, end to end. `cf-ray` proves the response came through Cloudflare rather than
# from something you reached directly.
curl -sI https://otj-services.com/health | head -1            # 200
curl -sI https://otj-services.com/health | grep -i '^cf-ray'  # present
```

**Check the rate limiter keys on the real client IP, not the edge.** This is the failure that
looks fine in a smoke test and takes everyone down under load — if `trusted_proxies` is wrong,
every visitor shares one bucket:

```bash
# The 429 body AND the header the mobile client depends on. The Caddyfile overrides the 429
# body with a custom message; this is the check that the override did not drop Retry-After.
for i in $(seq 1 12); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
done; echo
curl -si -X POST https://otj-services.com/auth/session \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}' | grep -i retry-after
```

The loop should turn from `401` to `429` at the eleventh request. Then, **from a different
network** (phone off wifi is enough), send one request: it must still be `401`, not `429`. A `429`
on the first request from a fresh IP means the limiter is bucketing by Cloudflare's edge address
and the whole user base is sharing one counter — fix `trusted_proxies` before going further.

If `Retry-After` is missing from the 429, delete the `handle_errors` block from the Caddyfile —
the header is worth more to the client than the custom message is to anyone.

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
Traffic from thousands of sources is absorbed at Cloudflare's edge; what reaches this origin has
already been through their L3/L4 and L7 protection. AWS Shield Standard still covers the
volumetric case for anything aimed straight at the Elastic IP, which the security group drops
anyway. Caddy's job here is the narrower one: bounding what a *legitimate-looking* client can
spend of the box's CPU.

## Keeping the Cloudflare ranges current

The one maintenance task this design creates. Cloudflare's ranges are in two places that must
agree — `trusted_proxies` in `Caddyfile` and `CLOUDFLARE_IPV4`/`CLOUDFLARE_IPV6` in
`aws/lib/otj-services-stack.ts`. They were last synced **2026-08-16**.

```bash
diff <(curl -s https://www.cloudflare.com/ips-v4) <(curl -s https://www.cloudflare.com/ips-v6)
```

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
