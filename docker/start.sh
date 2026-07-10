#!/usr/bin/env bash
set -eou pipefail

JAVA_OPTS=""
if [ "${JAVA_DEBUG:-false}" = "true" ]; then
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
  echo "Debug mode enabled — listening on port 5005"
fi

exec java $JAVA_OPTS -jar /app/app.jar
