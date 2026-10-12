# Shared helpers for the app E2E tests (sourced, not run).
#
# Each test drives a real app build on a simulator/emulator against a local server, with the
# CLI (built from this checkout) as the friend on the other end.

set -e
set -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
E2E_DIR="$ROOT/e2e"
WORK=${E2E_WORK:-$(mktemp -d)}
mkdir -p "$WORK"
CLI="$ROOT/cli/build/install/cli/bin/cli"
SERVER_PID=""

log() { echo "=== $* ==="; }

fail() {
  echo "❌ $*"
  exit 1
}

# Latest release tag reachable from HEAD unless BASE_REF is set.
baseline_ref() {
  echo "${BASE_REF:-$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' HEAD)}"
}

# A clean checkout of <ref> at <dir>, with local build config copied in.
checkout_ref() {
  local ref=$1 dir=$2
  rm -rf "$dir"
  git -C "$ROOT" worktree add --detach --quiet "$dir" "$ref"
  for f in local.properties local.gradle.kts; do
    [ -f "$ROOT/$f" ] && cp "$ROOT/$f" "$dir/"
  done
  return 0
}

build_server_and_cli() {
  (cd "$ROOT" && ./gradlew :server:installDist :cli:installDist --quiet)
}

start_server() {
  "$ROOT/server/build/install/server/bin/server" > "$WORK/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in {1..30}; do
    curl -s http://localhost:8080/ >/dev/null && return 0
    sleep 1
  done
  fail "server did not start (see $WORK/server.log)"
}

stop_server() {
  [ -n "$SERVER_PID" ] && kill -CONT "$SERVER_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
}

# A server outage that keeps mailbox state, like production's DynamoDB does (the local server's
# store is in memory, so a restart would wipe it): the frozen process accepts connections but
# never answers, so clients see timeouts, the same as a network blackhole.
freeze_server() { kill -STOP "$SERVER_PID"; }
thaw_server() { kill -CONT "$SERVER_PID"; }

# cli <state> <args...>: the CLI peer, with its state under $WORK.
cli() {
  local state=$1
  shift
  "$CLI" "$@" --state "$WORK/$state.json" 2>/dev/null
}

# retry <seconds> <command...>: rerun until it succeeds or time runs out.
retry() {
  local deadline=$((SECONDS + $1))
  shift
  until "$@"; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}

# Consistent copy of a live (WAL-mode) SQLite database.
snapshot_db() {
  python3 -I -c 'import sqlite3, sys; sqlite3.connect(sys.argv[1]).backup(sqlite3.connect(sys.argv[2]))' "$1" "$2"
}

# db_query <db> <sql>: first column of the first row ("" if none).
db_query() {
  python3 -I -c '
import sqlite3, sys
row = sqlite3.connect(sys.argv[1]).execute(sys.argv[2]).fetchone()
print("" if row is None or row[0] is None else row[0])
' "$1" "$2"
}

# The app's pending invite URL, read from a snapshot of its database with the CLI's own
# encoder (the CLI migrates the copy, never the app's database).
invite_url_from_db() {
  local db=$1
  rm -f "$WORK/appcopy.db" "$WORK/appcopy.json"
  snapshot_db "$db" "$WORK/appcopy.db"
  cli appcopy invites | grep "Invite URL:" | head -1 | awk '{print $3}'
}

# maestro_flow <flow>: runs e2e/flows/<flow> on $MAESTRO_DEVICE (or the only device).
# maestro_flow <flow> [-e NAME=value ...]
maestro_flow() {
  local flow=$1
  shift
  maestro ${MAESTRO_DEVICE:+--device "$MAESTRO_DEVICE"} test "$@" "$E2E_DIR/flows/$flow" || fail "maestro flow $flow failed"
}
