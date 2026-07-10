# otjServices

Java (JAX-RS/Grizzly + Dagger) backend automating QMUL OTJ hour submissions.

## Deployment topology

hours-api runs as a single rootless Podman container on a cloud VM, managed by a
systemd Quadlet unit — no docker-compose, no elevated container privileges.

- **Data**: MongoDB Atlas (free tier). `MONGO_URI` points at the Atlas SRV connection
  string; no local Mongo container.
- **Identity**: the app never talks to Tailscale directly. `tailscaled` runs as a
  normal host-level systemd service on the VM, and `tailscale serve` reverse-proxies
  tailnet HTTPS traffic to the app container on `127.0.0.1:8945`, injecting a
  `Tailscale-User-Login` header on every forwarded request. `TailscaleIdentityHelper`
  just reads that header — see the security invariant documented in that class.
- **Container**: `docker/otjService.Dockerfile` builds a minimal image (Maven build
  stage + `eclipse-temurin:25-jre` runtime, non-root `appuser`) with no Tailscale
  binaries, no Firefox/geckodriver. Run via the Podman Quadlet unit
  (`~/.config/containers/systemd/hours-api.container` on the VM), not `docker run`
  or compose.
- **CI/CD**: `.github/workflows/ci-cd.yml` runs `mvn test` on GitHub-hosted runners,
  then a self-hosted runner installed on the VM itself builds the image and restarts
  the Quadlet unit on push to `master`. No inbound SSH from GitHub; secrets
  (`MONGO_URI`, `ANTHROPIC_API_KEY`, `PASSWORD_ENCRYPTION_KEY`) live only in the VM's
  Quadlet `EnvironmentFile`, never in GitHub Actions.

Full step-by-step migration plan, including the VM/Tailscale/runner setup that has to
be done by hand once: [`deployment-migration-plan.html`](deployment-migration-plan.html).

## Building

```bash
mvn package
```

## Local container smoke test

```bash
podman build -t otj-hours-api:local -f docker/otjService.Dockerfile .
podman run -d --name otj-test -p 127.0.0.1:8945:8945 \
  -e MONGO_URI="mongodb+srv://..." \
  -e ANTHROPIC_API_KEY="sk-ant-..." \
  -e PASSWORD_ENCRYPTION_KEY="$(python3 -c 'import secrets, base64; print(base64.b64encode(secrets.token_bytes(32)).decode())')" \
  otj-hours-api:local
curl -s http://127.0.0.1:8945/health
```
