#!/usr/bin/env bash
# Installed once on the box at ~/otj-deploy/deploy.sh (as the otjapp user).
# Invoked by CI via `ssm:SendCommand` after a successful ECR push, or by hand
# for a rollback — see deployment-checklist.md step 6 and aws/README.md.
#
# Deploys two containers from one image URI: the main API (hours-api, :8945) and the
# tailnet-only invite admin API (admin-api, :8946). One URI for both is deliberate — it makes
# a mixed-version deploy impossible and a rollback a single argument.
#
# Usage: deploy.sh <full-ecr-image-uri-with-sha-tag>
set -euo pipefail

IMAGE_URI="${1:?usage: deploy.sh <image-uri>}"
REGION="eu-west-2"
REGISTRY="${IMAGE_URI%%/*}"
QUADLET_DIR="$HOME/.config/containers/systemd"
DEPLOY_DIR="$HOME/otj-deploy"

# unit name : template basename : health port
SERVICES=(
  "hours-api:hours-api.container.template:8945"
  "admin-api:admin-api.container.template:8946"
)

echo "==> Logging into $REGISTRY"
aws ecr get-login-password --region "$REGION" | podman login --username AWS --password-stdin "$REGISTRY"

echo "==> Pulling $IMAGE_URI"
podman pull "$IMAGE_URI"

echo "==> Rendering Quadlet units"
mkdir -p "$QUADLET_DIR"
for service in "${SERVICES[@]}"; do
  IFS=: read -r name template _ <<<"$service"
  sed "s|__IMAGE_URI__|$IMAGE_URI|" "$DEPLOY_DIR/$template" > "$QUADLET_DIR/$name.container"
done

echo "==> Restarting services"
systemctl --user daemon-reload
for service in "${SERVICES[@]}"; do
  IFS=: read -r name _ _ <<<"$service"
  systemctl --user restart "$name.service"
done

echo "==> Waiting for health checks"
for service in "${SERVICES[@]}"; do
  IFS=: read -r name _ port <<<"$service"
  healthy=false
  for _ in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$port/health" >/dev/null; then
      echo "    $name healthy."
      healthy=true
      break
    fi
    sleep 2
  done

  if [ "$healthy" = false ]; then
    echo "ERROR: $name did not become healthy" >&2
    journalctl --user -u "$name.service" --no-pager -n 50 >&2
    exit 1
  fi
done

echo "All services healthy."
