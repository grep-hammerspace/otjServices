# What is left of the cutover

The public edge is **live**. `https://otj-services.com` serves through Cloudflare to Caddy to the
app. What remains is a handful of follow-ups. Delete this file once they are done — ongoing
operations live in `deploy/prod/README.md`.

## Done — 2026-08-22

Verified against the live stack and the public endpoint, not assumed.

| | |
|---|---|
| App code on `master` | PR #21, then #39 and #41 |
| DNS — apex A record, proxied | `18.169.107.161`, orange cloud |
| Cloudflare WAF rate limiting rule | 3 req / 10 s per IP on the two `/auth` paths, Block 10 s |
| Tailnet listener moved off 443 | main API on 8444, admin on 8443 |
| Caddy | v2.11.4 from Cloudsmith, `caddy-ratelimit` v0.1.0, `apt-mark hold` |
| Origin CA certificate | `root:caddy` 644/640, expires 2041-08-18, Full (strict) |
| Caddyfile | loaded, Caddy owns `*:443`, no ACME attempted |
| Security group opens 443 | `sg-07a5d6b9dfdaf4e97`, 15 IPv4 + 7 IPv6 rules, **updated in place** |
| Public path works | `HTTP/2 200`, `cf-ray` present |
| Origin not bypassable | all three direct probes to the Elastic IP **time out** |
| Edge block shape | `429` + `retry-after: 10` — same as Caddy's, client backoff works |

The security group kept its id and the instance was never touched — see the `GroupDescription`
comment in `aws/lib/otj-services-stack.ts` for why that matters and what not to change.

## Outstanding

### 1. Confirm Caddy's limiter keys on the real client IP

**The one load-bearing check still unverified**, and the one that fails silently. If
`trusted_proxies` is not matching, `{client_ip}` falls back to the Cloudflare edge address and
every user in the world shares one rate-limit bucket — the site works perfectly until it doesn't.

It cannot be tested while the WAF rule is enabled: the edge blocks at 3 requests per 10 s, well
before Caddy's 10/min zone sees enough traffic. **Pause the rate limiting rule in the Cloudflare
dashboard**, run the paced loop in `deploy/prod/README.md` under "Caddy's own limiter", then
re-enable it. Nothing will remind you to re-enable it — the site behaves normally with it off.

### 2. Confirm the container healthcheck fix landed

`master` before #39 had no `curl` in the runtime image, so both containers reported `unhealthy`
while serving fine. #39 added it, and the deploy rebuilt the image, so this should now be clean:

```bash
ssm_run "sudo -u otjapp XDG_RUNTIME_DIR=/run/user/\$(id -u otjapp) podman ps --format '{{.Names}} {{.Status}}'"
```

Both should read `healthy` rather than `unhealthy`. If they still say `unhealthy`, the rebuild did
not pick up the Dockerfile change.

### 3. Mint the first invite

Nobody can sign up until this exists. From a tailnet device whose login is in
`ADMIN_ALLOWED_LOGINS`:

```bash
curl -s -X POST https://hours-api.<tailnet>.ts.net:8443/admin/invites \
  -H 'Content-Type: application/json' -d '{"note":"first code","expiresInDays":7}'
```

Then confirm the gate holds — the same call from a tailnet device **not** on the allowlist must
return `403`. If it succeeds, stop before minting anything real.

Close the loop against the public endpoint:

```bash
curl -s -X POST https://otj-services.com/auth/signup -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-XXXX-XXXX","username":"asad","password":"...","learnerId":"..."}'
```

### 4. Point the mobile client at the public name

`otj-mobile/.env.example` still reads `EXPO_PUBLIC_API_URL=https://example.ts.net`; it becomes
`https://otj-services.com`.

### 5. Reboot for the pending kernel

`needrestart` reports the box running `6.17.0-1019-aws` with `7.0.0-1011-aws` installed. This is
no longer free — 443 is public now, so a reboot is visible downtime. It is also still an untested
path: neither `caddy.service` nor the `tailscale serve` config has ever come back from a reboot on
this host. Pick a quiet moment and watch both come up.

## Worth knowing afterwards

- **Your rollback floor has moved.** With the two-service `deploy.sh` installed you cannot roll
  back past the commit that added the admin API: the old `start.sh` has no `APP_ROLE` handling, so
  an older image in the `admin-api` container would listen on 8945 while the Quadlet health-checks
  8946, never go healthy, and fail the whole deploy.
- **Taking the public endpoint down fast:** `sudo systemctl stop caddy`. The tailnet paths on 8444
  and 8443 keep working, so you keep admin access and a way to test.
- **Then delete this file.**
