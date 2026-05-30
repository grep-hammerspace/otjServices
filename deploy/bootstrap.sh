#!/usr/bin/env bash
# Bootstrap the full otjServices stack (mongo, mongo-express, app).
#
# Usage:
#   bash deploy/bootstrap.sh          # build and start in debug mode (default)
#   bash deploy/bootstrap.sh --prod   # build and start in production mode
#   bash deploy/bootstrap.sh --stop   # tear down stack (keeps Mongo data)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
ENV_FILE="$SCRIPT_DIR/../.env"

APP_PORT=8945
ME_PORT=8081
DEBUG_PORT=5005

# ── Helpers ───────────────────────────────────────────────────────────────────

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

# ── --stop shortcut ───────────────────────────────────────────────────────────

if [[ "${1:-}" == "--stop" ]]; then
  info "Stopping all containers..."
  docker-compose -f "$COMPOSE_FILE" down
  echo "Done. (Mongo data is preserved — run 'clean-mongo' to wipe it.)"
  exit 0
fi

# ── Mode ──────────────────────────────────────────────────────────────────────

MODE="debug"
for arg in "$@"; do
  [[ "$arg" == "--prod" ]] && MODE="prod"
done

if [ "$MODE" = "prod" ]; then
  export JAVA_DEBUG=false
else
  export JAVA_DEBUG=true
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

info "Starting full stack (mode: $MODE)..."
docker-compose -f "$COMPOSE_FILE" up -d

# ── Wait for services ─────────────────────────────────────────────────────────

wait_for "mongo-express" "http://localhost:$ME_PORT"
wait_for "app"           "http://localhost:$APP_PORT/health"

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
echo "  Stack is up."
echo ""
echo "  mongo-express  →  http://localhost:$ME_PORT"
echo "  app            →  http://localhost:$APP_PORT/otj-services"
if [ "$MODE" = "debug" ]; then
echo "  debugger       →  localhost:$DEBUG_PORT  (attach IDE remote debugger)"
fi
echo ""
echo "  Logs:  docker-compose -f deploy/docker-compose.yml logs -f app"
echo "  Stop:  bash deploy/bootstrap.sh --stop"
echo "  Wipe:  clean-mongo  (removes Mongo data volume)"
echo "  Deploy just mongo: docker compose -f deploy/docker-compose.yml up mongo -d (now you can run the server in debug mode)"
echo ""
