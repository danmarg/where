#!/usr/bin/env bash
# Runs TLC on one configuration of WhereSync: ./run-tlc.sh WhereSyncSafety.cfg
# TLA2TOOLS points at tla2tools.jar (CI downloads the pinned release; see .github/workflows/tla.yml).
set -euo pipefail
cd "$(dirname "$0")"
cfg="${1:?usage: run-tlc.sh <config.cfg>}"
jar="${TLA2TOOLS:?set TLA2TOOLS to the path of tla2tools.jar}"
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
exec java -XX:+UseParallelGC -Xss16m -cp "$jar" tlc2.TLC -workers auto -cleanup -metadir "$meta" -config "$cfg" WhereSync.tla
