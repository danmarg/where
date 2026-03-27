#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
GRADLE_USER_HOME=/Volumes/Ext/.gradle KONAN_DATA_DIR=/Volumes/Ext/.konan TMPDIR=/Volumes/Ext/tmp \
  nix develop --command ./gradlew :server:run
