#!/usr/bin/env bash
set -eou pipefail

# Initialize CSV state file if missing
[ -f /app/otjs.csv ] || echo "date,time-spent,start-time,comments,posted" > /app/otjs.csv

# Start Tailscale daemon in userspace mode (required in containers)
tailscaled --tun=userspace-networking --socks5-server=localhost:1055 &

# Wait for daemon to be ready
sleep 2

#R etrieve auth key to get container on logging tailnet
TAILSCALE_AUTHKEY=$(curl -s -X POST \
  -H "Authorization: Bearer $TAILSCALE_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"capabilities":{"devices":{"create":{"reusable":false,"ephemeral":true}}},"expirySeconds":3600}' \
  "https://api.tailscale.com/api/v2/tailnet/taila2e70a.ts.net/keys" \
  | jq -r '.key')

# Start up tailscale and connect to tailscale
tailscale up \
  --authkey="${TAILSCALE_AUTHKEY}" \
  --hostname="hours-api"

echo "Tailscale connected"

exec java -jar /app/app.jar