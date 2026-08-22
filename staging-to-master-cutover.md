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

Two further checks were run with the WAF rule temporarily paused, and both passed.

**Caddy's limiter fires at the right point and keys on the real client.** The paced loop returned
`401` ten times and `429` on the eleventh — `auth_burst` at 10/min. The 429 was Caddy's own
(`content-type: application/json`, the custom body) and **still carried `Retry-After`**, which is
the thing the `handle_errors` override was at risk of dropping.

`trusted_proxies` is matching. Two consecutive requests arrived through *different* Cloudflare edge
nodes and resolved to the same client:

```
remote_ip 172.68.229.95   client_ip 83.167.185.11
remote_ip 172.70.162.233  client_ip 83.167.185.11
```

Both counted into the same bucket. Had the key fallen back to the edge address they would have
been two separate buckets — so this rules out the silent failure where the whole user base shares
one counter.

**The container healthcheck fix landed.** Both `hours-api` and `admin-api` now report `healthy`;
before #39 added `curl` to the runtime image they reported `unhealthy` while serving perfectly.

One thing deliberately not changed: the access log records **full request headers**, so bearer
tokens will appear in it once real traffic starts. Accepted — the file is root-readable on a box
with no SSH and IAM-gated SSM access. Revisit if logs are ever shipped off the host, since that
changes who can read them.

## Outstanding

### 1. Mint the first invite

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

### 2. Point the mobile client at the public name

`otj-mobile/.env.example` still reads `EXPO_PUBLIC_API_URL=https://example.ts.net`; it becomes
`https://otj-services.com`.

### 3. Reboot for the pending kernel

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
