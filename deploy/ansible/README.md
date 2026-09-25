# The production box, as code

Everything the EC2 box runs is declared here and applied by Ansible, on the box, against itself.
Nobody changes the box by hand; they change this directory and merge. The *why* is in
`ansible-migration-plan.md`, and the order of operations for building a box is in
`ansible-deploy-checklist.md`.

| Path | What |
|---|---|
| `site.yml` | The one play. Role order matters; the file says why. |
| `group_vars/all.yml` | Everything configurable. **Nothing secret, ever.** |
| `roles/base` | Packages, the pinned AWS CLI v2, unattended upgrades (never rebooting), journald, sshd masked, and `otj-converge` itself (installed last). |
| `roles/otjapp` | The `otjapp` user, linger, and the `XDG_RUNTIME_DIR` every `systemctl --user` task needs |
| `roles/tailscale` | Install, join the tailnet (first run only), and `serve` 8443 → admin-api, 8444 → hours-api |
| `roles/edge` | HAProxy on 443, with `../haproxy/haproxy.cfg` checked before it goes live |
| `roles/app` | `otj-render-env`, the two Quadlets, the image in `otjapp`'s storage |
| `roles/verify` | Health, HAProxy, cert expiry, **no wildcard listener but 443**, the tailnet mappings |
| `ci-vars.yml` | The PR rehearsal's overrides only |
| `../bin/otj-converge` | The script that runs all of this on the box |
| `../haproxy/` | `haproxy.cfg` and `cloudflare-ips.lst` |

## How it runs

```
otj-converge <sha> [--check]        # as root on the box, via `ssm send-command` or an SSM session
  → pulls otj-hours-api:<sha> from ECR, copies /deploy out of it into /opt/otj/releases/<sha>/
  → /usr/bin/ansible-playbook site.yml -e image_tag=<sha>
```

The playbook ships **inside the app image**, so a release's app and box config always move
together, and a rollback to an older SHA brings back that SHA's Quadlets, proxy config and
playbook. `--check` prints what would change and changes nothing.

Every PR runs `.github/workflows/box.yml`. `box-static` runs ansible-lint, the syntax check,
shellcheck and `haproxy -c`. `box-rehearsal` applies the real playbook to a GitHub runner, which
has the same Ubuntu, Podman and systemd as the box. It applies it **twice** and fails if the second
run changes anything. Then it tests the rate limits, the loopback-only ports and the logs through
HAProxy.

## Secrets

**Ansible never reads, templates or prints a secret.** Each app unit's `ExecStartPre` runs
`otj-render-env <service>`, which fetches that service's parameters from Parameter Store with the
instance role and writes them to `/run/user/<uid>/otj/<service>.env`: tmpfs, `0600`, `otjapp`
only. The container loads that file. So `--diff` output, which reaches CloudWatch and the Actions
job, can't contain a secret, because no file Ansible manages holds one.

| Parameter | Service |
|---|---|
| `/otj/prod/mongo-uri` | both |
| `/otj/prod/anthropic-api-key` | hours-api |
| `/otj/prod/admin-allowed-logins` (`String`) | admin-api |
| `/otj/prod/tailscale-authkey` | the `tailscale` role, first run only |

A missing parameter fails that unit's start, and the error names it. To rotate one, run
`aws ssm put-parameter --overwrite …` and restart the unit: the restart renders the file again.

**The origin certificate is the one exception, and it lives on disk.** It is
`/etc/haproxy/certs/origin.pem`: cert then key, `0600 root`, installed by hand
(`ansible-deploy-checklist.md` step 4). The `edge` role only `stat`s it and fails early if it's
missing, and `verify` warns 90 days before it expires. It's a 15-year Cloudflare Origin CA
certificate, so there is no renewal job. To replace it, issue a new one in the Cloudflare
dashboard, install it, and restart `haproxy`. Once Session Manager logging is on, don't paste the
key into a session: plan §7 has the method to use then.

## How traffic reaches the app

Three ways in, and only three:

| Path | Port on the host | Reaches | Rate limited |
|---|---|---|---|
| Public: `otj-services.com` | 443, **from Cloudflare's ranges only** | Cloudflare → HAProxy → `127.0.0.1:8945` | yes: a Cloudflare WAF rule *and* HAProxy |
| Tailnet: main API | 8444 | `tailscale serve` → `127.0.0.1:8945` | **no** |
| Tailnet: admin API | 8443 | `tailscale serve` → `127.0.0.1:8946` | n/a |

Shell access is SSM Session Manager. There is no SSH port, no key, and sshd is masked.

**The origin sits behind the Cloudflare proxy.** `otj-services.com` is an orange-cloud record, so
it resolves to Cloudflare and the Elastic IP is never published. Cloudflare terminates the
visitor's TLS and makes a new TLS connection to this box, which presents the Origin CA certificate.
That arrangement is three settings that only work as a set:

| Setting | Where | If it drifts |
|---|---|---|
| Limits keyed on `src` **after** `set-src` | `haproxy.cfg` | Before the rewrite `src` is a Cloudflare edge node, so every user shares one bucket and they're all throttled together |
| `set-src` only from Cloudflare's ranges | `haproxy.cfg` + `cloudflare-ips.lst` | Anyone can pick their own bucket with a forged `CF-Connecting-IP`. And a missing *new* range buckets that range's users under the edge address. |
| 443 open to Cloudflare's ranges only | `aws/lib/otj-services-stack.ts` | Anyone who finds the Elastic IP skips Cloudflare, and can forge the header |

The Elastic IP not being in DNS doesn't make it secret. Certificate Transparency logs, DNS history
and plain scanning all turn up origins. The security group is what enforces this.

The tailnet path to the main API skips HAProxy entirely, so it isn't rate limited. That's an
operator path, reachable only from the tailnet. "I tested it over Tailscale and the limit didn't
fire" is expected.

**The main API's tailnet port is 8444, not 443, because HAProxy owns 443.** `tailscaled` binds 443
on the tailnet address specifically, which collides with HAProxy's wildcard bind. The `tailscale`
role runs before `edge`, and never serves on 443.

## The two rate limits, and why there are two

| | Where it runs | Scope | Limit | On trip |
|---|---|---|---|---|
| Cloudflare WAF rule | Cloudflare's edge | `/auth/signup`, `/auth/session` | **3 / 10 s per IP** | blocked for 10 s |
| `st_auth_burst` | HAProxy | `/auth/signup`, `/auth/session` | 10 / min per IP | 429, `Retry-After: 60` |
| `st_auth_daily` | HAProxy | `/auth/signup`, `/auth/session` | 250 / day per IP | 429, `Retry-After: 3600` |
| `st_api_burst` | HAProxy | everything else except `/health` | 120 / min per IP | 429, `Retry-After: 60` |

**The edge rule costs this box nothing to enforce**, because a request Cloudflare drops never
arrives. HAProxy is the backstop. They aren't redundant, because they catch different traffic:

- 3 / 10 s stops a burst, but permits 18 a minute sustained, which is *above* HAProxy's 10.
- 10 / min catches the patient attacker who paces requests to stay under the edge rule.

HAProxy's limits are also the only ones left if the origin is ever reached directly.

**Why they're shaped this way.** The limits are per source IP, and **users don't have their own
IPs**: on campus wifi every student shares one NAT'd address, and on mobile data they share a
carrier's CGNAT pool. A per-IP daily cap is a *shared* budget, and a flat 250/day across the whole
API would let ten students on campus lock out everyone else before lunch. So:

- **`/auth/signup` and `/auth/session`** get the burst limit *and* the daily cap. There's no user
  identity to key on yet, and every request costs a full bcrypt verify, including for usernames
  that don't exist, because `AuthResource` verifies against `DUMMY_HASH` to keep the timing
  constant. The app's own limiter keys on the submitted username, so it does nothing against an
  attacker who rotates usernames. On a burstable t3.small, sustained bcrypt drains the CPU credits
  and throttles the whole host.
- **Everything else** gets a burst ceiling only. It already needs a bearer token, and the expensive
  path is capped per user by `LlmQuotaService` at 10 LLM calls a day.
- **`/health`** is uncapped. A monitor polling every 5 minutes makes 288 requests a day, which would
  trip a daily cap on its own and take the alerting down with it.

The short windows are what matter. A 1-minute limit that trips recovers in a minute; a 24-hour one
that trips is a day-long outage for everyone behind that address.

**`Retry-After` is how the Expo client tells HAProxy's 429 from the app's own** (the LLM quota),
and how it knows when to retry. The rehearsal checks it's present and numeric.

**Two things to check about the edge rule:**
- **What status its block actually returns.** The client's backoff keys on `429` plus
  `Retry-After`. If Cloudflare sends a `403` or an HTML challenge page, the client shows an error it
  has no handling for.
- **3 / 10 s is tight for a shared address.** Three sign-ins inside ten seconds anywhere on one
  campus network trip it for everyone there. If a cohort onboards together, this is the first thing
  that will misfire, and it will be reported as "the app is broken".

## Verifying

```bash
# The public path end to end. `cf-ray` proves the response came through Cloudflare.
curl -sI https://otj-services.com/health | head -1            # 200
curl -sI https://otj-services.com/health | grep -i '^cf-ray'  # present
```

**The edge rule makes HAProxy's limit untestable from outside while the rule is on**: you can't
send 11 requests in a minute through Cloudflare without being blocked at the fourth. To test
HAProxy, pause the rule in the dashboard, then:

```bash
# 11 requests 4 s apart: ~44 s, so the 11th lands inside HAProxy's 1-minute window.
for i in $(seq 1 11); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST https://otj-services.com/auth/session \
    -H 'Content-Type: application/json' -d "{\"username\":\"t$i$RANDOM\",\"password\":\"y\"}"
  sleep 4
done; echo
```

It should turn from `401` to `429` at the eleventh request, and that `429` must carry
`Retry-After`. Then, still with the rule paused, send **one** request from a different network
(a phone off wifi is enough). It must be a `401`, not a `429`. A `429` on the first request from a
fresh address means everyone is sharing one bucket. **Re-enable the WAF rule afterwards.** Nothing
will remind you: the site works perfectly with it off.

The origin must only be reachable through Cloudflare. From anywhere that isn't Cloudflare or the
tailnet, all three of these must **time out**. A "connection refused" means the packet reached the
host.

```bash
curl --max-time 5 -k https://<ElasticIp>/health
curl --max-time 5 http://<ElasticIp>:8945/health
curl --max-time 5 http://<ElasticIp>:8946/admin/invites
```

## Keeping the Cloudflare ranges current

Cloudflare's ranges are in two places that must agree: `../haproxy/cloudflare-ips.lst` and
`CLOUDFLARE_IPV4` / `CLOUDFLARE_IPV6` in `aws/lib/otj-services-stack.ts`. Compare Cloudflare's
published lists against each file, not against each other:

```bash
# From the repo root. Prints any range Cloudflare publishes that a file is missing.
cf=$(curl -s https://www.cloudflare.com/ips-v4 https://www.cloudflare.com/ips-v6 | sort)
comm -23 <(echo "$cf") <(grep -v '^#' deploy/haproxy/cloudflare-ips.lst | sort)
comm -23 <(echo "$cf") <(grep -oE '"[0-9a-f:.]+/[0-9]+"' aws/lib/otj-services-stack.ts | tr -d '"' | sort)
```

No output means both agree. Anything printed goes into **both** files in one PR. The merge deploys
the stack and the converge reloads HAProxy.

A missing range fails *quietly*:
- **Missing from the security group:** visitors routed through that edge get timeouts, and only
  some of them, so it looks like a flaky network.
- **Missing from `cloudflare-ips.lst`:** HAProxy stops believing `CF-Connecting-IP` for those
  requests and buckets them under the edge address, so a slice of users is throttled as one.

## Going back to a directly reachable origin

It's the three settings above, in reverse, all together:

1. Switch the DNS record to grey cloud (DNS only).
2. Open 443 to `0.0.0.0/0` in the security group, and open 80 if you want ACME.
3. In `haproxy.cfg`, remove the `set-src` line and use a publicly trusted certificate.

Any one alone breaks the site. Grey cloud without step 2 blocks every visitor. Step 2 without step
1 exposes the origin while Cloudflare still fronts it.

## Reading the logs

Until log shipping lands (plan §10 step 7), from an SSM session:

```bash
sudo journalctl -u haproxy -f                    # the edge's access log, with real client IPs
sudo journalctl CONTAINER_NAME=hours-api -f      # the main API
sudo journalctl CONTAINER_NAME=admin-api -f      # the admin API
```

No workflow prints these, on purpose: GitHub job logs are readable by any signed-in GitHub user,
and these lines carry client IPs and `userId`s.

## Running it by hand

On the box, as root:

```bash
otj-converge <sha> --check     # what would change
otj-converge <sha>             # apply
ls /opt/otj/releases/          # the last five releases used
```

A change made by hand is undone by the next converge, and reported by the nightly drift check
once that's on. To keep a change, put it in this directory.
