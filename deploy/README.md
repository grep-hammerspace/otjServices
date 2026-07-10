# Deployment (rootless Podman, no sudo)

The stack (`mongo`, `mongo-express`, `app`) is orchestrated with `podman-compose` instead of Docker. Rootless Podman needs no `sudo` for build/run/stop.

```
cd deploy && nix-shell   # provides podman, podman-compose, curl, jq
bash bootstrap.sh        # build + start (debug mode by default)
bash bootstrap.sh --stop # stop the stack (Mongo data preserved)
clean-mongo              # stop + wipe Mongo data volume
```

## Identity: header instead of in-container Tailscale

The `app` container no longer runs `tailscaled` itself (that used to require `NET_ADMIN`/`NET_RAW`/`/dev/net/tun`, which rootless Podman can't grant anyway). Instead:

- `app`'s ports (`8945`, `5005`) are bound to `127.0.0.1` only — nothing but this host can reach it.
- `tailscaled` runs on **this host** (already tailnet-joined).
- One-time manual step on the host, outside the compose workflow:
  ```
  tailscale serve --bg --https=443 / http://127.0.0.1:8945
  ```
  This proxies tailnet traffic to the loopback app port and injects a `Tailscale-User-Login` header, which `TailscaleIdentityHelper` reads directly (`src/main/java/com/github/grepHammerspace/tailscale/TailscaleIdentityHelper.java`).
- Before relying on this in production, verify against current Tailscale docs: the exact header name/casing `tailscale serve` injects, that MagicDNS + HTTPS Certificates are enabled on the tailnet, and whether the `serve` config needs to be re-applied after a host reboot.

**Trust invariant:** the header is only trustworthy because the app is reachable exclusively via loopback — if the port binding or network topology ever changes, re-examine this assumption.
