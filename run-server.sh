#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

USE_NIX=false
for arg in "$@"; do
  [[ "$arg" == "--nix" ]] && USE_NIX=true
done

run() {
  if $USE_NIX; then
    nix develop --command "$@"
  else
    "$@"
  fi
}

GRADLE_USER_HOME=$HOME/.gradle KONAN_DATA_DIR=$HOME/.konan TMPDIR=/tmp \
  run ./gradlew :server:run
