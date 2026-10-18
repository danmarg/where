#!/usr/bin/env bash
# Runs TLC on one configuration of WhereSync: formal/tla/run-tlc.sh WhereSyncSafety.cfg
# TLA2TOOLS points at tla2tools.jar (CI downloads the pinned release; see .github/workflows/tla.yml).
# Exits with TLC's status: 0 when every property holds, non-zero on a violation or an error.
set -euo pipefail
cfg="${1:?usage: run-tlc.sh <config.cfg>}"
cfg="$(cd "$(dirname "$cfg")" 2>/dev/null && pwd)/$(basename "$cfg")"
[ -f "$cfg" ] || cfg="$(cd "$(dirname "$0")" && pwd)/$(basename "$1")"
jar="${TLA2TOOLS:?set TLA2TOOLS to the path of tla2tools.jar}"
cd "$(dirname "$0")"
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
status=0
java -XX:+UseParallelGC -Xss16m -cp "$jar" tlc2.TLC -workers auto -cleanup -metadir "$meta" -config "$cfg" WhereSync.tla || status=$?
exit "$status"
