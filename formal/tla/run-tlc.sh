#!/usr/bin/env bash
# Runs TLC on one configuration of WhereSync: formal/tla/run-tlc.sh WhereSyncSafety.cfg
# TLA2TOOLS points at tla2tools.jar (CI downloads the pinned release; see .github/workflows/tla.yml).
# Exits with TLC's status: 0 when every property holds, non-zero on a violation or an error.
set -euo pipefail
arg="${1:?usage: run-tlc.sh <config.cfg>}"
here="$(cd "$(dirname "$0")" && pwd)"
# The configuration as given (relative to the caller), or else by name next to this script.
if [ -f "$arg" ]; then
  cfg="$(cd "$(dirname "$arg")" && pwd)/$(basename "$arg")"
elif [ -f "$here/$(basename "$arg")" ]; then
  cfg="$here/$(basename "$arg")"
else
  echo "run-tlc.sh: no configuration $arg" >&2
  exit 2
fi
jar="${TLA2TOOLS:?set TLA2TOOLS to the path of tla2tools.jar}"
cd "$here"
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
status=0
java -XX:+UseParallelGC -Xss16m -cp "$jar" tlc2.TLC -workers auto -cleanup -metadir "$meta" -config "$cfg" WhereSync.tla || status=$?
exit "$status"
