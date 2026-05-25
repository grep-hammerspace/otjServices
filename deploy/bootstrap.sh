#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: .env not found at $ENV_FILE"
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

cd "$SCRIPT_DIR"

echo "Starting mongo and mongo-express..."
docker compose up -d mongo mongo-express

echo "Waiting for mongo-express to be ready..."
for i in $(seq 1 15); do
  if curl -sf http://localhost:8081 > /dev/null 2>&1; then
    echo ""
    echo "  mongo-express: http://localhost:8081"
    echo ""
    exit 0
  fi
  sleep 2
done

echo ""
echo "  mongo-express may still be starting. Try: http://localhost:8081"
echo ""
