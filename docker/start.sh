#!/usr/bin/env bash
set -eou pipefail

exec java -cp /app/app.jar com.github.grepHammerspace.Main
