#!/usr/bin/env bash
# iOS upgrade E2E: the last release's app, paired with a friend, is upgraded in place to this
# checkout's app. It must keep its friend and session (and must NOT show the "Re-pair your
# friends" device-binding notice), and keep exchanging locations both ways. Finally the keychain
# is reset to stand in for a device transfer, which must discard the session and show the notice.
#
# Requires macOS with Xcode, xcodegen and maestro. Usage: e2e/ios-upgrade-test.sh
# Env: BASE_REF (default: latest v* tag), BASELINE_APP / HEAD_APP (prebuilt .app, skips a build).
source "$(dirname "$0")/lib.sh"

BUNDLE=net.af0.WhereApp
SIM=""
TEST_STATUS=failed
cleanup() {
  stop_server
  [ -n "$SIM" ] && { xcrun simctl shutdown "$SIM" 2>/dev/null; xcrun simctl delete "$SIM" 2>/dev/null; } || true
  for d in "$WORK/src-baseline" "$WORK/src-head"; do
    git -C "$ROOT" worktree remove --force "$d" 2>/dev/null || true
  done
  [ "$TEST_STATUS" = passed ] && rm -rf "$WORK" || echo "Failed; state kept in $WORK"
}
trap cleanup EXIT

# build_app <ref> <out.app>: a Release-configuration simulator build of <ref> (optimized, as
# shipped), pointed at the local server and
# ad-hoc signed (an unsigned build gets no keychain access, which would make the device-binding
# check silently skip and this test prove nothing).
build_app() {
  local ref=$1 out=$2 src
  src="$WORK/src-$(basename "$out" .app)"
  log "Building iOS app at $ref"
  checkout_ref "$ref" "$src"
  local cfg="$src/ios/Sources/Where/ServerConfig.swift"
  sed -i '' 's|static let httpBaseUrl = "https://where-api.af0.net"|static let httpBaseUrl = "http://127.0.0.1:8080"|' "$cfg"
  grep -q '"http://127.0.0.1:8080"' "$cfg" || fail "could not point $ref's simulator build at the local server"
  touch "$src/ios/Local.xcconfig"
  (cd "$src/ios" && xcodegen --quiet)
  (cd "$src" && ./gradlew :shared:assembleSharedReleaseXCFramework --quiet)
  (cd "$src/ios" && xcodebuild build -scheme Where -configuration Release -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd-$(basename "$out" .app)" \
    CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
    -quiet)
  rm -rf "$out"
  cp -R "$WORK/dd-$(basename "$out" .app)/Build/Products/Release-iphonesimulator/Where.app" "$out"
}

app_db() {
  local data
  data=$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)
  echo "$data/Library/Application Support/databases/where.db"
}

# app_query <sql>: against a snapshot of the app's live database.
app_query() {
  snapshot_db "$(app_db)" "$WORK/snap.db"
  db_query "$WORK/snap.db" "$1"
}

friend_count() { app_query "SELECT count(*) FROM Friends"; }

friend_count_is() { [ "$(friend_count)" = "$1" ]; }

app_has_lat() { [ "$(app_query "SELECT lastLat FROM Friends")" = "$1" ]; }

cli_got_lat_prefix() { cli bob poll --once | grep -q "Location from .*: $1"; }

# Nudge the simulated location between two points >50m apart so the app has a reason to send.
NUDGE=0
nudge_and_check_cli() {
  local lat=$1
  NUDGE=$((1 - NUDGE))
  xcrun simctl location "$SIM" set "$lat$NUDGE,13.4$NUDGE" >/dev/null
  sleep 5
  cli_got_lat_prefix "$lat"
}

# After pausing, moving around for a while must not deliver a single location to the friend.
assert_no_sends_while_paused() {
  cli bob poll --once >/dev/null # drain anything sent before the pause
  for lat in 52.61 52.62 52.63 52.64 52.65 52.66; do
    xcrun simctl location "$SIM" set "$lat,13.45" >/dev/null
    sleep 8
  done
  if cli bob poll --once | grep -q "Location from"; then
    fail "a location was sent while sharing was paused"
  fi
  return 0
}

notice_pending() { xcrun simctl spawn "$SIM" defaults read "$BUNDLE" discarded_session_names >/dev/null 2>&1; }

BASE=$(baseline_ref)
log "iOS upgrade test: $BASE -> HEAD ($(git -C "$ROOT" rev-parse --short HEAD)), work dir $WORK"

BASELINE_APP=${BASELINE_APP:-$WORK/baseline.app}
HEAD_APP=${HEAD_APP:-$WORK/head.app}
[ -d "$BASELINE_APP" ] || build_app "$BASE" "$BASELINE_APP"
[ -d "$HEAD_APP" ] || build_app HEAD "$HEAD_APP"
build_server_and_cli
start_server

SIM=$(xcrun simctl create where-e2e "iPhone 17")
export MAESTRO_DEVICE=$SIM
xcrun simctl boot "$SIM"
xcrun simctl bootstatus "$SIM" -b >/dev/null
xcrun simctl location "$SIM" set 52.520,13.405

log "Baseline app pairs with the CLI"
xcrun simctl install "$SIM" "$BASELINE_APP"
xcrun simctl privacy "$SIM" grant location-always "$BUNDLE"
maestro_flow ios/create-invite.yaml
URL=$(invite_url_from_db "$(app_db)")
[ -n "$URL" ] || fail "no pending invite in the app's database"
cli bob join "$URL" Cli | grep -q "Posted KeyExchangeInit" || fail "CLI could not join the app's invite"
maestro_flow ios/name-friend.yaml
retry 60 friend_count_is 1 || fail "the app did not complete pairing"

retry 120 nudge_and_check_cli 52.53 || fail "CLI never received a location from the baseline app"
cli bob send 48.137 11.575 --force | grep -q successfully || fail "CLI send failed"
retry 60 app_has_lat 48.137 || fail "baseline app never received the CLI's location"
echo "✓ Baseline app paired and exchanged locations both ways"

log "Upgrade to HEAD in place"
xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$SIM" "$HEAD_APP"
maestro_flow ios/assert-upgraded.yaml
sleep 5
[ -n "$(app_query "SELECT marker FROM DeviceBinding")" ] ||
  fail "no device-binding marker after the upgrade: the check didn't run (keychain unavailable?)"
[ "$(friend_count)" = 1 ] || fail "the upgrade lost the friend"
notice_pending && fail "the upgrade queued a 'Re-pair your friends' notice"

cli bob send 48.208 16.373 --force | grep -q successfully || fail "CLI send failed"
retry 60 app_has_lat 48.208 || fail "upgraded app never received the CLI's location"
retry 120 nudge_and_check_cli 52.56 || fail "CLI never received a location from the upgraded app"
echo "✓ Upgrade kept the friend and session, with no re-pair notice"

log "Pausing stops sending"
maestro_flow ios/pause.yaml
assert_no_sends_while_paused
echo "✓ Nothing sent while paused"

log "Keychain reset (stands in for a device transfer) discards the session"
xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl keychain "$SIM" reset
maestro_flow ios/assert-repair-notice.yaml
[ "$(friend_count)" = 0 ] || fail "sessions survived a database that no longer matches the device"
echo "✓ Mismatched database discarded, with the re-pair notice"

TEST_STATUS=passed
echo ""
echo "✅ iOS upgrade test passed ($BASE -> HEAD)"
