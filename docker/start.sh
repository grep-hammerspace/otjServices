#!/usr/bin/env bash
set -eou pipefail

# APP_ROLE=api (default, :8945) or admin (:8946). One image, so both are always the same build.
APP_ROLE="${APP_ROLE:-api}"

JAVA_OPTS=""
if [ "${JAVA_DEBUG:-false}" = "true" ]; then
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
  echo "Debug mode enabled — listening on port 5005"
fi

case "$APP_ROLE" in
  api)
    exec java $JAVA_OPTS -cp /app/app.jar com.github.grepHammerspace.Main
    ;;
  admin)
    exec java $JAVA_OPTS -cp /app/app.jar com.github.grepHammerspace.admin.AdminMain
    ;;
  *)
    echo "ERROR: unknown APP_ROLE '$APP_ROLE' (expected 'api' or 'admin')" >&2
    exit 1
    ;;
esac
