#!/bin/bash
set -e
cd "$(dirname "$0")"

USE_NIX=false
PASSTHROUGH_ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--nix" ]]; then
    USE_NIX=true
  else
    PASSTHROUGH_ARGS+=("$arg")
  fi
done

run() {
  if $USE_NIX; then
    nix develop --command "$@"
  else
    "$@"
  fi
}

if [ ${#PASSTHROUGH_ARGS[@]} -eq 0 ]; then
  run ./gradlew :cli:run --quiet
else
  run ./gradlew :cli:run --quiet --args="${PASSTHROUGH_ARGS[*]}"
fi
