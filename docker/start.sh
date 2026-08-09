#!/usr/bin/env bash
set -eou pipefail

# One image, two entrypoints. The shaded jar carries every class, so the role only decides which
# main class runs — which in turn guarantees the API and the admin API are always the same build.
#   APP_ROLE=api    (default) the main REST API on 8945
#   APP_ROLE=admin            the tailnet-only invite admin API on 8946
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
