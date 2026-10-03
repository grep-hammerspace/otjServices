#!/usr/bin/env bash
# Self-hosted stack (mongo + app), served on your tailnet.
# Usage: bash deploy/bootstrap.sh [--prod | --stop]
#   --prod  detach under nohup, so it survives closing the terminal
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/podman-compose.yaml"
ENV_FILE="$SCRIPT_DIR/../.env"
REQUIRED_VARS=(MONGO_USER MONGO_PASSWORD ANTHROPIC_API_KEY)

APP_PORT=8945

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

require() {
  command -v "$1" &>/dev/null || die "'$1' not found — install it or enter nix-shell first."
}

wait_for() {
  local name="$1" url="$2" attempts="${3:-30}"
  info "Waiting for $name..."
  for i in $(seq 1 "$attempts"); do
    if curl -s -o /dev/null "$url" 2>/dev/null; then
      echo "    $name ready."; return 0
    fi
    echo "    Attempt $i/$attempts — sleeping 2s..."; sleep 2
  done
  die "$name did not become ready in time."
}

tailscale_operator_set() {
  local operator
  operator=$(tailscale debug prefs 2>/dev/null | jq -r '.OperatorUser // empty')
  [ -n "$operator" ] && [ "$operator" = "$USER" ]
}

if [[ "${1:-}" == "--stop" ]]; then
  info "Stopping all containers..."
  podman-compose -f "$COMPOSE_FILE" down
  echo "Done. (Mongo data is preserved — run 'clean-mongo' to wipe it.)"
  exit 0
fi

DETACH=false
for arg in "$@"; do
  [[ "$arg" == "--prod" ]] && DETACH=true
  [[ "$arg" == "--no-detach" ]] && DETACH=false
done

if [ "$DETACH" = "true" ]; then
  LOG="/tmp/otj-deploy-$(date +%Y%m%d-%H%M%S).log"
  info "Detaching from terminal. Logs: $LOG"
  nohup bash "$0" --no-detach >"$LOG" 2>&1 &
  disown
  echo "    Running as PID $! — follow with: tail -f $LOG"
  exit 0
fi

require podman
require podman-compose
require curl
require jq
require tailscale

[ -f "$ENV_FILE" ] || die ".env not found at $ENV_FILE — copy .env.example and fill it in."
set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

missing=()
for var in "${REQUIRED_VARS[@]}"; do
  [ -n "${!var:-}" ] || missing+=("$var")
done
[ ${#missing[@]} -eq 0 ] || die "set these in .env: ${missing[*]}"
export OTJ_DRIVER_LOG_LEVEL="${OTJ_DRIVER_LOG_LEVEL:-INFO}"

tailscale_operator_set || die "tailscale serve needs root unless you are its operator. Run once:
    sudo tailscale set --operator=$USER"

info "Building app image (Maven build runs inside Podman, rootless)..."
podman-compose -f "$COMPOSE_FILE" build app

info "Starting the stack..."
podman-compose -f "$COMPOSE_FILE" up -d
podman-compose -f "$COMPOSE_FILE" ps

wait_for "app" "http://localhost:$APP_PORT/health"

info "Serving the API on your tailnet..."
tailscale serve --bg --https=443 "http://127.0.0.1:$APP_PORT" >/tmp/otj-tailscale-serve.log 2>&1 \
  || { cat /tmp/otj-tailscale-serve.log; die "tailscale serve failed — is HTTPS enabled for your tailnet?"; }

TS_NAME=$(tailscale status --self --json | jq -r '.Self.DNSName' | sed 's/\.$//')

echo ""
echo "  Stack is up."
echo ""
echo "  In the app, tap \"Hosting the backend yourself?\" and enter:"
echo "    https://$TS_NAME"
echo ""
echo "  Logs:  podman-compose -f deploy/podman-compose.yaml logs -f app"
echo "  Stop:  bash deploy/bootstrap.sh --stop"
echo "  Wipe:  clean-mongo  (removes the Mongo data volume)"
echo ""
