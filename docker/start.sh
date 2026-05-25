#!/usr/bin/env bash
set -eou pipefail

# Initialize CSV state file if missing
[ -f /app/otjs.csv ] || echo "date,time-spent,start-time,comments,posted" > /app/otjs.csv

# Start Tailscale daemon
tailscaled &

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

# Register port 8945 with Tailscale's userspace netstack so incoming packets are forwarded to the local server
echo "Tailscale connected"

JAVA_OPTS=""
if [ "${JAVA_DEBUG:-false}" = "true" ]; then
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
  echo "Debug mode enabled — listening on port 5005"
fi

exec java $JAVA_OPTS -jar /app/app.jar