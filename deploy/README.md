# Deployment (rootless Podman, no sudo)

The stack (`mongo`, `mongo-express`, `app`, `admin`) is orchestrated with `podman-compose` instead of Docker. Rootless Podman needs no `sudo` for build/run/stop.

```
cd deploy && nix-shell   # provides podman, podman-compose, curl, jq
bash bootstrap.sh        # build + start (debug mode by default)
bash bootstrap.sh --stop # stop the stack (Mongo data preserved)
clean-mongo              # stop + wipe Mongo data volume
```

## The two app containers

`app` and `admin` run from the **same image** with different entrypoints, selected by `APP_ROLE` in `docker/start.sh`. The shaded jar carries both main classes, so one build produces both roles and they can never drift apart.

| Container | Role | Port | Reached via |
|---|---|---|---|
| `app` | main REST API, backs the mobile client | `127.0.0.1:8945` | `tailscale serve --https=443` |
| `admin` | mint/revoke signup invite codes | `127.0.0.1:8946` | `tailscale serve --https=8443` |

Those `Reached via` ports are the **self-host** ones, wired by `bootstrap.sh`. The AWS box
differs: the main API is public on 443 through Caddy and its tailnet listener has moved to 8444
to leave 443 free. See `deploy/prod/README.md`.

The admin API is a separate server rather than a path on the main API because the main API sits
behind a public domain. That has now happened — `api.otj-services.com`, terminated by Caddy — and
the decision paid off exactly as expected: a path under the main API would have inherited that
exposure the moment the proxy landed, whereas a distinct port on a loopback binding could not.

## Identity: header instead of in-container Tailscale

The app containers do not run `tailscaled` themselves (that used to require `NET_ADMIN`/`NET_RAW`/`/dev/net/tun`, which rootless Podman can't grant anyway). Instead:

- Every published port is bound to `127.0.0.1` only — nothing but this host can reach any of them.
- `tailscaled` runs on **this host** (already tailnet-joined).
- One-time manual step on the host, outside the compose workflow:
  ```
  tailscale serve --bg --https=443  http://127.0.0.1:8945   # main API
  tailscale serve --bg --https=8443 http://127.0.0.1:8946   # admin API
  ```
  `bootstrap.sh` does both automatically if the Tailscale operator has been delegated to your user (`sudo tailscale set --operator=$USER`, once).
- `tailscale serve` injects a `Tailscale-User-Login` header naming the authenticated tailnet user. `AdminIdentityFilter` (`src/main/java/com/github/grepHammerspace/admin/AdminIdentityFilter.java`) requires it and checks it against the `ADMIN_ALLOWED_LOGINS` allowlist.
- Before relying on this in production, verify against current Tailscale docs: the exact header name/casing `tailscale serve` injects, **that `serve` strips a client-supplied header of that name rather than passing it through**, that MagicDNS + HTTPS Certificates are enabled on the tailnet, and whether the `serve` config needs re-applying after a host reboot.

**Trust invariant:** the header is only trustworthy because the app is reachable exclusively via loopback — if the port binding or network topology ever changes, re-examine this assumption. `AdminIdentityFilter` stops being a security control the moment `8946` is published wider than `127.0.0.1`.

**Being on the tailnet is not the same as being an operator.** Phones and laptops join the tailnet to *use* the app. That is why the allowlist exists on top of the loopback binding, and why an unset `ADMIN_ALLOWED_LOGINS` denies everyone rather than allowing anyone.

## Using the admin API

```bash
# mint (over the tailnet — serve injects the identity header for you)
curl -s -X POST https://<host>.<tailnet>.ts.net:8443/admin/invites \
  -H 'Content-Type: application/json' \
  -d '{"note":"sam’s phone","expiresInDays":7}'
# -> 201 {"code":"OTJ-K7QP-3XMN", "status":"ACTIVE", ...}

# list
curl -s https://<host>.<tailnet>.ts.net:8443/admin/invites

# revoke an unclaimed code
curl -s -X DELETE https://<host>.<tailnet>.ts.net:8443/admin/invites/OTJ-K7QP-3XMN
# -> 204; 404 if unknown, 409 if it has already been claimed
```

Locally, where there is no `tailscale serve` in front, set the header yourself:

```bash
curl -s -X POST http://localhost:8946/admin/invites \
  -H 'Content-Type: application/json' \
  -H 'Tailscale-User-Login: you@example.com' \
  -d '{"note":"dev"}'
```

That is not a bypass — reaching `127.0.0.1:8946` at all already means you are on the host, which is the same thing the trust model assumes in production.
