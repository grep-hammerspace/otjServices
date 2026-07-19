#!/usr/bin/env bash
# Installed once on the box at ~/otj-deploy/deploy.sh (as the otjapp user).
# Invoked by CI via `ssm:SendCommand` after a successful ECR push, or by hand
# for a rollback — see deployment-checklist.md step 6 and aws/README.md.
#
# Usage: deploy.sh <full-ecr-image-uri-with-sha-tag>
set -euo pipefail

IMAGE_URI="${1:?usage: deploy.sh <image-uri>}"
REGION="eu-west-2"
REGISTRY="${IMAGE_URI%%/*}"
QUADLET_DIR="$HOME/.config/containers/systemd"
TEMPLATE="$HOME/otj-deploy/hours-api.container.template"
UNIT="$QUADLET_DIR/hours-api.container"

echo "==> Logging into $REGISTRY"
aws ecr get-login-password --region "$REGION" | podman login --username AWS --password-stdin "$REGISTRY"

echo "==> Pulling $IMAGE_URI"
podman pull "$IMAGE_URI"

echo "==> Rendering Quadlet unit"
mkdir -p "$QUADLET_DIR"
sed "s|__IMAGE_URI__|$IMAGE_URI|" "$TEMPLATE" > "$UNIT"

echo "==> Restarting hours-api.service"
systemctl --user daemon-reload
systemctl --user restart hours-api.service

echo "==> Waiting for health check"
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8945/health >/dev/null; then
    echo "Healthy."
    exit 0
  fi
  sleep 2
done

echo "ERROR: hours-api did not become healthy" >&2
journalctl --user -u hours-api.service --no-pager -n 50 >&2
exit 1
