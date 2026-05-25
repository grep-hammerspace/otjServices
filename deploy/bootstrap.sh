#!/usr/bin/env bash
# Bootstrap the full otjServices stack (mongo, mongo-express, app).
#
# Usage:
#   bash deploy/bootstrap.sh          # build app image, start stack
#   bash deploy/bootstrap.sh --stop   # tear down stack (keeps Mongo data)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
ENV_FILE="$SCRIPT_DIR/../.env"

APP_PORT=8945
ME_PORT=8081

# ── Helpers ───────────────────────────────────────────────────────────────────

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

require() {
  command -v "$1" &>/dev/null || die "'$1' not found — install it or enter nix-shell first."
}

wait_for() {
  local name="$1" url="$2" attempts="${3:-20}"
  info "Waiting for $name..."
  for i in $(seq 1 "$attempts"); do
    if curl -s -o /dev/null "$url" 2>/dev/null; then
      echo "    $name ready."; return 0
    fi
    echo "    Attempt $i/$attempts — sleeping 2s..."; sleep 2
  done
  die "$name did not become ready in time."
}

# ── --stop shortcut ───────────────────────────────────────────────────────────

if [[ "${1:-}" == "--stop" ]]; then
  info "Stopping all containers..."
  docker-compose -f "$COMPOSE_FILE" down
  echo "Done. (Mongo data is preserved — run 'clean-mongo' to wipe it.)"
  exit 0
fi

# ── Pre-flight ────────────────────────────────────────────────────────────────

require docker
require docker-compose

[ -f "$ENV_FILE" ] || die ".env not found at $ENV_FILE"
set -a; source "$ENV_FILE"; set +a

# ── Build app image ───────────────────────────────────────────────────────────

info "Building app image (Maven build runs inside Docker)..."
docker-compose -f "$COMPOSE_FILE" build app

# ── Start stack ───────────────────────────────────────────────────────────────

info "Starting full stack..."
docker-compose -f "$COMPOSE_FILE" up -d

# ── Wait for services ─────────────────────────────────────────────────────────

wait_for "mongo-express" "http://localhost:$ME_PORT"  30
wait_for "app"           "http://localhost:$APP_PORT/health" 30

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
echo "  Stack is up."
echo ""
echo "  mongo-express  →  http://localhost:$ME_PORT"
echo "  app            →  http://localhost:$APP_PORT/otj-services"
echo ""
echo "  Logs:  docker-compose -f deploy/docker-compose.yml logs -f"
echo "  Stop:  bash deploy/bootstrap.sh --stop"
echo "  Wipe:  clean-mongo  (removes Mongo data volume)"
echo ""
