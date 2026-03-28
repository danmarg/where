#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
GRADLE_USER_HOME=$HOME/.gradle KONAN_DATA_DIR=$HOME/.konan TMPDIR=/tmp \
  nix develop --command ./gradlew :server:run
