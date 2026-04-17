#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")/.."

if [ -f .envrc ]; then
  source .envrc
fi

export TMPDIR="${TMPDIR:-/tmp}"

run() {
  "$@"
}

if [ $# -eq 0 ]; then
  run ./gradlew :cli:run --quiet
else
  run ./gradlew :cli:run --quiet --args="$*"
fi
