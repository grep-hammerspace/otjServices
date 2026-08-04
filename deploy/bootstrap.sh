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
COMPOSE_FILE="$SCRIPT_DIR/podman-compose.yaml"
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

# True if `tailscale serve` can run as $USER without sudo (operator delegated once via
# `tailscale set --operator=$USER`). See deploy/README.md for why this is needed.
tailscale_operator_set() {
  command -v tailscale &>/dev/null || return 1
  local operator
  operator=$(tailscale debug prefs 2>/dev/null | jq -r '.OperatorUser // empty')
  [ -n "$operator" ] && [ "$operator" = "$USER" ]
}

# ── --stop shortcut ───────────────────────────────────────────────────────────

if [[ "${1:-}" == "--stop" ]]; then
  info "Stopping all containers..."
  podman-compose -f "$COMPOSE_FILE" down
  echo "Done. (Mongo data is preserved — run 'clean-mongo' to wipe it.)"
  exit 0
fi

# ── Mode ──────────────────────────────────────────────────────────────────────

MODE="debug"
DETACH=true
for arg in "$@"; do
  [[ "$arg" == "--prod"       ]] && MODE="prod"
  [[ "$arg" == "--no-detach"  ]] && DETACH=false
done

if [ "$MODE" = "prod" ]; then
  export JAVA_DEBUG=false
else
  export JAVA_DEBUG=true
fi

# In prod mode detach from the SSH session so the deploy survives disconnects.
# The script re-execs itself under nohup with --no-detach to skip this block.
if [ "$MODE" = "prod" ] && [ "$DETACH" = "true" ]; then
  LOG="/tmp/otj-deploy-$(date +%Y%m%d-%H%M%S).log"
  info "Prod mode — detaching from terminal. Logs: $LOG"
  nohup bash "$0" --prod --no-detach >"$LOG" 2>&1 &
  disown
  echo "    Running as PID $! — follow with: tail -f $LOG"
  exit 0
fi

# ── Pre-flight ────────────────────────────────────────────────────────────────

require podman
require podman-compose

[ -f "$ENV_FILE" ] || die ".env not found at $ENV_FILE"
set -a; source "$ENV_FILE"; set +a

# Compose substitutes unset variables with an empty string (or leaves the literal
# ${VAR} through, depending on the podman-compose version), so a missing key surfaces
# as a container crash-loop minutes later instead of an error here. Catch it now.
# LLM_BASE_URL and LLM_MODEL are omitted on purpose — AppModule.envOrDefault defaults them.
MISSING=()
for var in MONGO_USER MONGO_PASSWORD LLM_API_KEY PASSWORD_ENCRYPTION_KEY; do
  [ -n "${!var:-}" ] || MISSING+=("$var")
done
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "ERROR: missing required variable(s) in $ENV_FILE:" >&2
  for var in "${MISSING[@]}"; do echo "         $var" >&2; done
  if [[ " ${MISSING[*]} " == *" PASSWORD_ENCRYPTION_KEY "* ]]; then
    echo "" >&2
    echo "       Generate one with:" >&2
    echo "         echo \"PASSWORD_ENCRYPTION_KEY=\$(openssl rand -base64 32)\" >> $ENV_FILE" >&2
    echo "       Note: changing this key makes already-stored passwords undecryptable." >&2
  fi
  exit 1
fi

# AES-256 needs exactly 32 bytes; anything else fails inside PasswordCipher at startup.
KEY_BYTES=$(printf '%s' "$PASSWORD_ENCRYPTION_KEY" | base64 -d 2>/dev/null | wc -c) || KEY_BYTES=0
[ "$KEY_BYTES" -eq 32 ] || die "PASSWORD_ENCRYPTION_KEY must be base64 that decodes to 32 bytes for AES-256, got $KEY_BYTES."

# ── Build app image ───────────────────────────────────────────────────────────

info "Building app image (Maven build runs inside Podman, rootless)..."
podman-compose -f "$COMPOSE_FILE" build app

# ── Start stack ───────────────────────────────────────────────────────────────

info "Starting full stack (mode: $MODE)..."
podman-compose -f "$COMPOSE_FILE" up -d

info "Containers started — current status:"
podman-compose -f "$COMPOSE_FILE" ps

# ── Wait for services ─────────────────────────────────────────────────────────

if [ "$MODE" = "debug" ]; then
  wait_for "mongo-express" "http://localhost:$ME_PORT"
fi
wait_for "app" "http://localhost:$APP_PORT/health"

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
echo "  Stack is up."
echo ""
if [ "$MODE" = "debug" ]; then
echo "  mongo-express  →  http://localhost:$ME_PORT"
fi
echo "  app            →  http://localhost:$APP_PORT/otj-services"
if [ "$MODE" = "debug" ]; then
echo "  debugger       →  localhost:$DEBUG_PORT  (attach IDE remote debugger)"
fi
echo ""
echo "  Logs:  podman-compose -f deploy/podman-compose.yaml logs -f app"
echo "  Stop:  bash deploy/bootstrap.sh --stop"
echo "  Wipe:  clean-mongo  (removes Mongo data volume)"
echo "  Deploy just mongo: podman-compose -f deploy/podman-compose.yaml up mongo -d (now you can run the server in debug mode)"
echo ""

# ── tailscale serve (optional — makes the app reachable from the tailnet) ─────

if command -v tailscale &>/dev/null; then
  if tailscale_operator_set; then
    info "Wiring up tailscale serve (operator already delegated to $USER)..."
    if tailscale serve --bg --https=443 "http://127.0.0.1:$APP_PORT" >/tmp/otj-tailscale-serve.log 2>&1; then
      TS_NAME=$(tailscale status --self --json 2>/dev/null | jq -r '.Self.DNSName' | sed 's/\.$//')
      echo "  tailnet        →  https://$TS_NAME/otj-services"
    else
      echo "  WARNING: 'tailscale serve' failed — see /tmp/otj-tailscale-serve.log"
      cat /tmp/otj-tailscale-serve.log
    fi
  else
    echo "  NOTE: tailscale operator is not set for '$USER' — 'tailscale serve' needs root without it."
    echo "        Run this once (one-time sudo, never needed again):"
    echo "          sudo tailscale set --operator=$USER"
    echo "        Then re-run this script to wire up tailscale serve automatically."
  fi
  echo ""
fi
