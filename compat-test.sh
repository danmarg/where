#!/usr/bin/env bash
# Cross-version compatibility test: the CLI from the last release (default: the latest v* tag
# on main) against the CLI built from this checkout, through this checkout's server.
#
#   A. Upgrade in place: a client paired and mid-conversation on the old version upgrades its
#      database, keeps its session and pending invite, and keeps talking to a peer still on the
#      old version (both directions, across several DH ratchet steps).
#   B. Fresh pairing across versions, with either side creating the invite.
#
# The CLI shares the apps' protocol, storage schema and migrations (shared module), so this
# covers wire-format and schema compatibility. It does not exercise platform code; v* tags come
# from the Android release lane, so for iOS the baseline is the nearest proxy.
#
# Usage: ./compat-test.sh [baseline-ref]
set -e
set -o pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)

if [ -f .envrc ]; then
  source .envrc
elif [ -f .env ]; then
  source .env
fi

BASE_REF=${1:-$(git describe --tags --abbrev=0 --match 'v*' HEAD)}
TEST_DIR=$(mktemp -d)
BASE_DIR="$TEST_DIR/baseline-src"
echo "=== Compat: baseline $BASE_REF ($(git rev-parse --short "$BASE_REF")), state in $TEST_DIR ==="

SERVER_PID=""
TEST_STATUS="failed"
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  git -C "$ROOT" worktree remove --force "$BASE_DIR" 2>/dev/null || true
  if [ "$TEST_STATUS" == "passed" ]; then
    rm -rf "$TEST_DIR"
  else
    echo "Compat test failed; keeping $TEST_DIR for debugging (server log: $TEST_DIR/server.log)."
  fi
}
trap cleanup EXIT

echo "=== Compat: building the current server and CLI ==="
./gradlew :server:installDist :cli:installDist --quiet

echo "=== Compat: building the baseline CLI ==="
git worktree add --detach --quiet "$BASE_DIR" "$BASE_REF"
[ -f local.properties ] && cp local.properties "$BASE_DIR/"
[ -f local.gradle.kts ] && cp local.gradle.kts "$BASE_DIR/"
(cd "$BASE_DIR" && ./gradlew :cli:installDist --quiet)

NEW="$ROOT/cli/build/install/cli/bin/cli"
OLD="$BASE_DIR/cli/build/install/cli/bin/cli"

./server/build/install/server/bin/server > "$TEST_DIR/server.log" 2>&1 &
SERVER_PID=$!
for _ in {1..30}; do
  curl -s http://localhost:8080/ >/dev/null && break
  sleep 1
done

fail() {
  echo "❌ $*"
  exit 1
}

# cli <binary> <state-name> <args...>
cli() {
  local bin=$1 state=$2
  shift 2
  "$bin" "$@" --state "$TEST_DIR/$state.json"
}

invite_url() {
  cli "$1" "$2" invite "$3" --no-wait | grep "Invite URL:" | awk '{print $3}'
}

# pair <inviter-bin> <inviter-state> <inviter-name> <joiner-bin> <joiner-state> <joiner-name>
pair() {
  local url
  url=$(invite_url "$1" "$2" "$3")
  [ -n "$url" ] || fail "$2 could not create an invite"
  cli "$4" "$5" join "$url" "$6" | grep -q "Posted KeyExchangeInit" || fail "$5 could not join $2's invite"
  cli "$1" "$2" poll --once | grep -q "Established session with $6" || fail "$2 did not complete pairing with $6"
}

STEP=0
# exchange <from-bin> <from-state> <from-name> <to-bin> <to-state>: send a unique location and
# assert the recipient decrypts exactly it.
exchange() {
  STEP=$((STEP + 1))
  local lat lng out
  lat=$(awk "BEGIN {print 10 + $STEP / 1000}")
  lng=$(awk "BEGIN {print 20 + $STEP / 1000}")
  cli "$1" "$2" send "$lat" "$lng" --force | grep -q "successfully" || fail "$2 failed to send (step $STEP)"
  out=$(cli "$4" "$5" poll --once)
  echo "$out" | grep -q "Location from $3: $lat, $lng" || {
    echo "$out"
    fail "$5 did not receive $3's location $lat, $lng (step $STEP)"
  }
}

# round_trips <n> <a-bin> <a-state> <a-name> <b-bin> <b-state> <b-name>
# Each change of direction is a DH ratchet step.
round_trips() {
  local n=$1
  shift
  for _ in $(seq 1 "$n"); do
    exchange "$1" "$2" "$3" "$4" "$5"
    exchange "$4" "$5" "$6" "$1" "$2"
  done
}

# Tables and columns, so a migrated database can be compared with a freshly created one.
db_schema() {
  python3 -I -c '
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = \"table\" AND name NOT LIKE \"sqlite_%\" ORDER BY name")]
for t in tables:
    cols = sorted((c[1], c[2], c[3], c[4], c[5]) for c in db.execute(f"PRAGMA table_info({t})"))
    print(t, cols)
' "$TEST_DIR/$1.db"
}

db_query() {
  python3 -I -c 'import sqlite3, sys; print(sqlite3.connect(sys.argv[1]).execute(sys.argv[2]).fetchone()[0])' "$TEST_DIR/$1.db" "$2"
}

echo "=== A. Upgrade in place ==="
pair "$OLD" alice Alice "$OLD" bob Bob
round_trips 3 "$OLD" alice Alice "$OLD" bob Bob
OLD_VERSION=$(db_query alice "PRAGMA user_version")

# State the upgrade must carry over: an invite nobody has accepted yet, and messages from Bob
# that Alice hasn't polled.
PENDING_URL=$(invite_url "$OLD" alice Alice)
[ -n "$PENDING_URL" ] || fail "alice could not create the pending invite"
cli "$OLD" bob send 30.0001 40.0001 --force | grep -q "successfully" || fail "bob failed to send before the upgrade"

echo "  Alice upgrades (schema $OLD_VERSION)"
OUT=$(cli "$NEW" alice poll --once)
echo "$OUT" | grep -q "Location from Bob: 30.0001, 40.0001" || {
  echo "$OUT"
  fail "upgraded alice did not receive the message bob sent before the upgrade"
}
NEW_VERSION=$(db_query alice "PRAGMA user_version")
echo "  Schema $OLD_VERSION -> $NEW_VERSION"
cli "$NEW" fresh list >/dev/null
[ "$NEW_VERSION" = "$(db_query fresh "PRAGMA user_version")" ] || fail "migrated schema version $NEW_VERSION != fresh $(db_query fresh "PRAGMA user_version")"
diff <(db_schema fresh) <(db_schema alice) || fail "migrated database schema differs from a freshly created one"
cli "$NEW" alice list | grep -q ": Bob (" || fail "upgraded alice lost Bob"
round_trips 3 "$NEW" alice Alice "$OLD" bob Bob

# The pending invite survived the migration and can still be accepted.
cli "$OLD" carol join "$PENDING_URL" Carol | grep -q "Posted KeyExchangeInit" || fail "carol could not join the pending invite"
cli "$NEW" alice poll --once | grep -q "Established session with Carol" || fail "upgraded alice could not complete the pre-upgrade invite"
round_trips 1 "$NEW" alice Alice "$OLD" carol Carol
echo "✓ Upgrade kept the session, the backlog and the pending invite"

echo "=== B. Fresh pairing across versions ==="
pair "$NEW" dave Dave "$OLD" erin Erin
round_trips 3 "$NEW" dave Dave "$OLD" erin Erin
echo "✓ New invites, old joins"
pair "$OLD" frank Frank "$NEW" grace Grace
round_trips 3 "$OLD" frank Frank "$NEW" grace Grace
echo "✓ Old invites, new joins"

TEST_STATUS="passed"
echo ""
echo "✅ Compat test passed against $BASE_REF"
