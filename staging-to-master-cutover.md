# What is left of the cutover

One-time. The multi-user auth rollout and the admin API are **already live**; what remains is the
public Caddy edge. Delete this file once the last box is ticked.

Operational detail lives in `deploy/prod/README.md` — this is the ordering, not the instructions.

## Status as of 2026-08-22

Verified against `origin/master` and the live stack, not assumed.

| | State |
|---|---|
| App code on `master` (auth, admin API, rate limiting, LLM quota) | **done** — merged by PR #21, `583ddff` |
| App deployed and serving | **done** — both containers, `/health` 200 on 8945 and 8946 |
| Admin env file, deploy script, Quadlet templates on the box | **done** |
| DNS — apex A record, proxied | **done** — `18.169.107.161`, orange cloud |
| Cloudflare WAF rate limiting rule | **done** — 3 req / 10 s per IP on the two `/auth` paths, block 10 s |
| Tailnet listener moved off 443 | **done** — 2026-08-22, main API on 8444, 443 free |
| Caddy installed on the box | **done** — 2026-08-22, v2.11.4 from Cloudsmith + `caddy-ratelimit` v0.1.0 |
| Origin CA certificate | **done** — 2026-08-22, installed `root:caddy` 644/640, expires 2041-08-18 |
| Caddyfile installed | **done** — 2026-08-22, reloaded clean, Caddy active on 443 |
| PR #39 merged → security group opens 443 | **not done** |
| First invite minted | **not done** |

`OtjServicesStack`: instance `i-06dd830c8fbde9685`, Elastic IP `18.169.107.161`, security group
`sg-07a5d6b9dfdaf4e97` with **zero ingress rules** — confirmed 2026-08-22.

## The ordering that matters

**Provision the box completely, then merge PR #39. Not the other way round.**

CI is `on: push: branches: [master]` and its first deploy step is `npx cdk deploy
OtjServicesStack`. The Cloudflare ingress rules are in PR #39. So the merge opens 443 on its own,
within minutes, with nothing further from you. Merge before Caddy is listening and the public name
is down for however long provisioning takes.

The old version of this document had merging as step 1.1 and installing Caddy as 1.5. That order
is wrong and is the reason this section exists.

Two consequences of the merge worth knowing before you trigger it:

- **The security group is replaced, not amended.** PR #39 changes its `description`, and
  `GroupDescription` is immutable in CloudFormation. `cdk deploy` creates a new group, attaches it,
  and deletes the old one. No instance interruption, but the group id changes.
- **The container healthcheck fix ships with it.** `master`'s runtime image has no `curl`, so both
  containers report `unhealthy` while serving perfectly well — the `HealthCmd` exits 127. PR #39
  adds `curl` to `docker/otjService.Dockerfile`, which needs the rebuild that the merge triggers.
  Until then, **do not use `podman ps` health as a signal**; curl from the host instead.

## Steps

Full instructions for each are in `deploy/prod/README.md` under "Provisioning the edge" — the
section numbers below match.

### 1. Get a shell and the file helper

```bash
cd ~/Projects/personal/java/otjServices && nix-shell    # aws, cdk, node, jq
aws ssm start-session --target i-06dd830c8fbde9685 --region eu-west-2
```

No SSH port and no SSH key. `sudo -i` for root, `sudo -u otjapp -i` for the app user — and
`systemctl --user` as `otjapp` needs `XDG_RUNTIME_DIR=/run/user/$(id -u otjapp)` or it fails with
`Failed to connect to bus`.

SSM has no `scp`. For pushing files: `source deploy/prod/push-file.sh`, which gives you
`push_file`, `ssm_run` and `verify_files`. It base64-encodes locally so content survives JSON
encoding and two layers of shell quoting.

### 2. Install Caddy with the rate limiting module — **done 2026-08-22**

Caddy v2.11.4 from Cloudsmith, with `http.handlers.rate_limit` v0.1.0 via `caddy add-package`.

Two traps hit on the way, both now written up in `deploy/prod/README.md` step 2:

- **Ubuntu `noble/universe` ships its own `caddy 2.6.2`**, and `add-package` did not exist until
  2.7. The first attempt installed that one: apt reported complete success, the service unit was
  created, and nothing was visibly wrong until `add-package` failed with `unknown command`. The
  root cause was that `/etc/apt/sources.list.d/caddy-stable.list` had never been written, so apt
  silently fell back. `apt-cache policy caddy` must name cloudsmith as the candidate's origin
  before you install.
- **The multi-line `curl … \` + `| sudo tee` forms from Caddy's own docs do not survive this SSM
  shell.** The continuation gets split, and the downloaded content is concatenated onto the
  command line instead of written to the file — silently. Both the keyring and the source list
  were missing afterwards. Use the single-line, pipe-free forms in the README.

`caddy` is held at 2.11.4, and the service has been restarted so the **running** process is the
`add-package` binary — `sudo caddy list-modules --packages` reports `Non-standard modules: 1`.
That restart is what makes `reload` safe at step 5; see the note there.

### 3. Move the main API's tailnet listener off 443 — **done 2026-08-22**

Main API now on 8444, admin API unchanged on 8443, and `ss -lntp | grep ':443 '` returns nothing —
so Caddy can bind 443 in step 5.

Anything still calling the tailnet name on `:443` is now broken and needs `:8444`. The smoke test
in `deployment-checklist.md` was one; it has been updated.

### 4. Issue and install the Origin CA certificate — **done 2026-08-22**

Cloudflare → SSL/TLS → Origin Server → Create Certificate. **The private key is shown once.**
Install as root: cert `644 root:caddy`, key `640 root:caddy`. Set the zone's SSL/TLS mode to
**Full (strict)** — plain "Full" accepts any certificate at all, which wastes the exercise.

There is no ACME here. Let's Encrypt cannot reach an origin that only admits Cloudflare.

### 5. Install the Caddyfile — **done 2026-08-22**

```bash
push_file deploy/prod/Caddyfile /etc/caddy/Caddyfile root 644
sudo -u caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

Validate before reloading. `validate` also opens the certificate and key, so a missing or
unreadable pair fails here rather than at reload. If it says `rate_limit is not a registered
directive`, step 2 did not take — do not "fix" it by deleting the rate limit blocks.

**Validate as `caddy`, never as root** — including via `ssm_run`, which runs as root. Validating
provisions the `log` block's file writer, which *creates* `/var/log/caddy/access.log`; as root
that file lands `root:root` 600, the service (which runs as `caddy`) then cannot open it, and the
reload fails with `permission denied` pointing at the log writer. The validation step causes the
outage it was meant to prevent. Hit on 2026-08-22; recovery is
`chown caddy:caddy /var/log/caddy/access.log`.

**`reload` only works if Caddy has been restarted since `add-package`.** Reload hands the config
to the process already in memory over the admin API; it does not re-exec. A process started before
the binary swap has no `rate_limit` module and rejects the config with an unknown-module error
that reads exactly like a broken Caddyfile. `sudo systemctl restart caddy` once, at step 2, avoids
this entirely.

Caddy is now listening on 443 but unreachable: the security group still admits nothing.

### 6. Merge PR #39 — **outstanding**

This is the cutover. CI opens 443 from Cloudflare's ranges and rebuilds the image with the
healthcheck fix.

### 7. Verify — **outstanding**

Run the checks in `deploy/prod/README.md` under "Verifying". The three that matter:

- `/health` returns 200 with a `cf-ray` header — traffic is going through Cloudflare to Caddy.
- The origin is unreachable directly: `curl -k --max-time 5 https://18.169.107.161/health` must
  **time out**. A refusal means the packet reached the host; an answer means the proxy is
  bypassable and the whole design is moot.
- Caddy's limiter keys on the real client IP, not the edge address. **This needs the WAF rule
  paused** — 3 req/10 s at the edge stops you generating enough traffic to reach Caddy's 10/min
  zone. Re-enable it afterwards; nothing will remind you.

### 8. Mint the first invite — **outstanding**

From a tailnet device whose login is in `ADMIN_ALLOWED_LOGINS`:

```bash
curl -s -X POST https://hours-api.<tailnet>.ts.net:8443/admin/invites \
  -H 'Content-Type: application/json' -d '{"note":"first code","expiresInDays":7}'
```

Then confirm the gate holds — the same call from a tailnet device **not** on the allowlist must
return `403`. If it succeeds, stop before minting anything real.

Sign up against the public endpoint to close the loop:

```bash
curl -s -X POST https://otj-services.com/auth/signup -H 'Content-Type: application/json' \
  -d '{"inviteCode":"OTJ-XXXX-XXXX","username":"asad","password":"...","learnerId":"..."}'
```

## After the cutover

- **Update the mobile client.** `otj-mobile/.env.example` still reads
  `EXPO_PUBLIC_API_URL=https://example.ts.net`; it becomes `https://otj-services.com`.
- **Check what the edge block returns.** The client backs off on `429` + `Retry-After`, which is
  what Caddy sends. Cloudflare's block may not be the same response — if it is a `403` or a
  challenge page, the client needs handling for it. See `deploy/prod/README.md`.
- **Your rollback floor has moved.** With the two-service `deploy.sh` installed you cannot roll
  back past the commit that added the admin API: `master`'s old `start.sh` has no `APP_ROLE`
  handling, so an older image in the `admin-api` container would listen on 8945 while the Quadlet
  health-checks 8946, never go healthy, and fail the whole deploy.
- **Taking the public endpoint down fast:** `sudo systemctl stop caddy`. The tailnet paths on 8444
  and 8443 keep working, so you keep admin access and a way to test.
- **Delete this file.**
