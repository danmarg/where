#!/bin/bash
set -e
cd "$(dirname "$0")"

PASSTHROUGH_ARGS=("$@")

run() {
  "$@"
}

if [ ${#PASSTHROUGH_ARGS[@]} -eq 0 ]; then
  run ./gradlew :cli:run --quiet
else
  run ./gradlew :cli:run --quiet --args="${PASSTHROUGH_ARGS[*]}"
fi
