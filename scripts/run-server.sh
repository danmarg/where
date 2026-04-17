#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")/.."

if [ -f .envrc ]; then
  source .envrc
fi

export TMPDIR="${TMPDIR:-/tmp}"

./gradlew :server:run
