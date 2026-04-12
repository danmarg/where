#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

run() {
  "$@"
}

GRADLE_USER_HOME=$HOME/.gradle KONAN_DATA_DIR=$HOME/.konan TMPDIR=/tmp \
  run ./gradlew :server:run
