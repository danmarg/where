#!/usr/bin/env bash
# Android upgrade E2E: the last release's app pairs with the CLI through an invite link and
# exchanges locations, is upgraded in place (adb install -r) to this checkout's app, and must
# keep its friend and session. Then, on the upgraded app: opening the same invite link again
# must not pair twice, pausing must stop location updates and sends, and backup must be refused.
#
# Uses minified release builds of the fdroid flavor: it reads the platform GPS/network providers,
# which emulator test providers drive directly. The gms flavor's fused location runs inside Play
# services, which on a fresh emulator restarts itself mid-test and drops or never delivers test
# fixes. Upgrade-relevant code (shared, database, E2EE, invites, pause, backup rules) is the same
# in both flavors. Runs against a booted emulator (google_apis image, which has su).
# Usage: e2e/android-upgrade-test.sh
# Env: BASE_REF (default: latest v* tag), BASELINE_APK / HEAD_APK (prebuilt, skips a build),
#      ABI (default: x86_64), E2E_BUILD_ONLY=1 (build the APKs and stop, e.g. before booting
#      the emulator).
source "$(dirname "$0")/lib.sh"

PKG=net.af0.where
ABI=${ABI:-x86_64}
TEST_STATUS=failed
cleanup() {
  stop_server
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
  if [ "$TEST_STATUS" != passed ] && [ -z "$E2E_BUILD_ONLY" ]; then
    adb shell dumpsys location > "$WORK/dumpsys-location.txt" 2>/dev/null || true
  fi
  for d in "$WORK/src-baseline" "$WORK/src-head"; do
    git -C "$ROOT" worktree remove --force "$d" 2>/dev/null || true
  done
  [ "$TEST_STATUS" = passed ] && rm -rf "$WORK" || echo "Failed; state kept in $WORK"
}
trap cleanup EXIT

# build_apk <ref> <out.apk>: a minified release build of <ref> (as shipped, so R8 problems show
# up here), signed with the debug key and pointed at the host's local server.
build_apk() {
  local ref=$1 out=$2 src
  src="$WORK/src-$(basename "$out" .apk)"
  log "Building Android release app at $ref"
  checkout_ref "$ref" "$src"
  local gradle="$src/android/build.gradle.kts"
  sed -i.bak 's|"SERVER_HTTP_URL", "\\"https://where-api.af0.net\\""|"SERVER_HTTP_URL", "\\"http://10.0.2.2:8080\\""|' "$gradle" && rm -f "$gradle.bak"
  grep -q '"http://10.0.2.2:8080\\""' "$gradle" && ! grep -q 'where-api.af0.net' "$gradle" ||
    fail "could not point $ref's release build at the local server"
  local nsc="$src/android/src/androidMain/res/xml/network_security_config.xml"
  # (No backup file: Android's resource merger rejects anything in res/xml/ that isn't .xml.)
  sed -i.bak 's/cleartextTrafficPermitted="false"/cleartextTrafficPermitted="true"/' "$nsc" && rm -f "$nsc.bak"
  local ks="$HOME/.android/debug.keystore"
  mkdir -p "$HOME/.android"
  [ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  (cd "$src" && ./gradlew :android:assembleStandardFdroidRelease --quiet \
    -Pandroid.injected.signing.store.file="$ks" -Pandroid.injected.signing.store.password=android \
    -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android)
  cp "$(ls "$src"/android/build/outputs/apk/standardFdroid/release/*"$ABI"*.apk | head -1)" "$out"
}

# app_query <sql>: against a copy of the app's database read off the device with su (the
# release build isn't debuggable, so run-as can't reach it). The app uses rollback-journal mode,
# so the main file holds every committed transaction.
app_query() {
  adb exec-out su 0 cat "/data/data/$PKG/databases/where.db" > "$WORK/app.db" 2>/dev/null || return 1
  db_query "$WORK/app.db" "$1"
}

friend_count_is() { [ "$(app_query "SELECT count(*) FROM Friends")" = "$1" ]; }

app_has_lat() { [ "$(app_query "SELECT lastLat FROM Friends")" = "$1" ]; }

cli_got_any_location() { cli alice poll --once | grep -q "Location from"; }

cli_established() { cli alice poll --once | grep -q "Established session"; }

cli_bob_established() { cli bob poll --once | grep -q "Established session"; }

cli_got_lat_prefix() {
  cli alice poll --once | grep "Location from" | tee -a "$WORK/cli-locations.txt" | grep -q ": $1"
}

# set_location <lat> <lng>: feeds the test gps and network providers (the app uses whichever is
# enabled, gps first).
set_location() {
  local p
  for p in gps network; do
    adb shell cmd location providers set-test-provider-location $p --location "$1,$2" --accuracy 10
  done
}

# move_and_publish <lat>: moves there and gets the app to send it. Pausing and resuming
# re-registers location updates, in case the app went stationary. A test provider only reports a
# location when it's set, so keep feeding it, as a real one would. The app drops a send within
# 30 s of the previous one, and asks for updates only on moves of 200 m+ (and ignores a repeat of
# its last fix), so alternate between two points ~700 m apart at the same latitude: each fix is a
# real move, and the first after the throttle gets sent. The pause/resume is repeated every 60 s
# in case a registration is lost.
FEED_N=0
move_and_publish() {
  local deadline=$((SECONDS + 240)) renudge=0
  while [ $SECONDS -lt $deadline ]; do
    if [ $SECONDS -ge $renudge ]; then
      set_location "$1" 13.42
      maestro_flow android/pause.yaml
      maestro_flow android/resume.yaml
      renudge=$((SECONDS + 60))
    fi
    feed_and_check_cli "$1" && return 0
  done
  return 1
}

feed_and_check_cli() {
  FEED_N=$((FEED_N + 1))
  set_location "$1" "$([ $((FEED_N % 2)) = 0 ] && echo 13.42 || echo 13.43)"
  sleep 3
  cli_got_lat_prefix "$1"
}

# After pausing, moving around for a while must not deliver a single location to the friend.
assert_no_sends_while_paused() {
  cli alice poll --once >/dev/null # drain anything sent before the pause
  for lat in 52.61 52.62 52.63 52.64 52.65 52.66; do
    set_location "$lat" 13.45
    sleep 8
  done
  if cli alice poll --once | grep -q "Location from"; then
    fail "a location was sent while sharing was paused"
  fi
  return 0
}

open_link() {
  adb shell "am start -W -a android.intent.action.VIEW -d '$1' $PKG" >/dev/null
}

# The app's current location listeners (not the event history, whose lines say "registration").
location_listening() {
  adb shell dumpsys location | grep -qE "^ +[0-9]+/$PKG/[0-9A-F]+ Request\["
}
location_not_listening() { ! location_listening; }

BASE=$(baseline_ref)
log "Android upgrade test: $BASE -> HEAD ($(git -C "$ROOT" rev-parse --short HEAD)), work dir $WORK"

BASELINE_APK=${BASELINE_APK:-$WORK/baseline.apk}
HEAD_APK=${HEAD_APK:-$WORK/head.apk}
[ -f "$BASELINE_APK" ] || build_apk "$BASE" "$BASELINE_APK"
[ -f "$HEAD_APK" ] || build_apk HEAD "$HEAD_APK"
if [ -n "$E2E_BUILD_ONLY" ]; then
  TEST_STATUS=passed
  exit 0
fi
build_server_and_cli
start_server

# Not `adb root`: mock locations are refused to uid 0. su is used only to read the database.
adb wait-for-device
[ "$(adb shell su 0 id -u | tr -d '\r')" = 0 ] || fail "no su on this emulator image (use a google_apis image)"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb shell cmd location set-location-enabled true
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider gps --requiresSatellite --supportsAltitude
adb shell cmd location providers add-test-provider network --requiresNetwork --supportsAltitude
adb shell cmd location providers set-test-provider-enabled gps true
adb shell cmd location providers set-test-provider-enabled network true
adb logcat -c
# Streamed to a file: Maestro clears the device log buffer when it starts a flow.
adb logcat -v time > "$WORK/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
set_location 52.520 13.405

log "Baseline app pairs with the CLI"
adb install "$BASELINE_APK" >/dev/null
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done
# Exempt from battery optimization, as a user who accepts the app's prompt would be (the
# prompt otherwise covers the screen on launch).
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
URL=$(cli alice invite Alice --no-wait | grep "Invite URL:" | awk '{print $3}')
[ -n "$URL" ] || fail "CLI could not create an invite"
open_link "$URL"
maestro_flow android/name-friend.yaml
if ! retry 30 cli_established; then
  maestro_flow android/tap-add-if-shown.yaml # the tap didn't register
  retry 60 cli_established || fail "the app's key exchange never reached the CLI"
fi
maestro_flow android/dismiss-naming-if-shown.yaml
retry 60 friend_count_is 1 || fail "the app did not record the friend"

move_and_publish 52.53 || fail "CLI never received a location from the baseline app"
cli alice send 48.137 11.575 --force | grep -q successfully || fail "CLI send failed"
retry 60 app_has_lat 48.137 || fail "baseline app never received the CLI's location"
echo "✓ Baseline app paired and exchanged locations both ways"

log "Upgrade to HEAD in place"
cli alice poll --once >/dev/null # drain, so a location seen below came from the upgraded app
adb install -r "$HEAD_APK" >/dev/null
maestro_flow android/launch.yaml
retry 30 friend_count_is 1 || fail "the upgrade lost the friend"
[ "$(app_query "PRAGMA user_version")" -ge 5 ] || fail "the database wasn't migrated"
cli alice send 48.208 16.373 --force | grep -q successfully || fail "CLI send failed"
retry 60 app_has_lat 48.208 || fail "upgraded app never received the CLI's location"
# Any location, not a new position: the app may come back up stationary, heartbeating its
# cached fix. The next step checks a move.
retry 180 cli_got_any_location || fail "CLI never received a location from the upgraded app"
echo "✓ Upgrade kept the friend and session"

log "The upgraded app publishes where it moved to"
move_and_publish 52.58 || fail "CLI never received the position the upgraded app moved to"
echo "✓ Upgraded app sends its new position"

# On a second CLI identity. (Not the baseline's invite: the app only records invites accepted
# since it started tracking them, so one accepted before the upgrade can still be replayed until
# it expires.)
log "The upgraded app pairs with a new invite, and opening it again doesn't pair twice"
URL2=$(cli bob invite Bob --no-wait | grep "Invite URL:" | awk '{print $3}')
[ -n "$URL2" ] || fail "CLI could not create an invite"
open_link "$URL2"
maestro_flow android/name-friend.yaml
if ! retry 30 cli_bob_established; then
  maestro_flow android/tap-add-if-shown.yaml # the tap didn't register
  retry 60 cli_bob_established || fail "the upgraded app's key exchange never reached the CLI"
fi
retry 60 friend_count_is 2 || fail "the upgraded app did not record the new friend"
open_link "$URL2"
retry 20 grep -q "invite already accepted" "$WORK/logcat.txt" || fail "the app didn't reject the replayed invite"
maestro_flow android/invite-already-used.yaml
friend_count_is 2 || fail "re-opening an accepted invite paired again"
echo "✓ New pairing works; accepted invite rejected on replay"

# The foreground service itself stays up while paused, by design; location updates stop.
log "Pausing stops location updates and sending"
retry 30 location_listening || fail "the app isn't listening for location while sharing"
maestro_flow android/pause.yaml
retry 30 location_not_listening || fail "the app still listens for location after pausing"
assert_no_sends_while_paused
echo "✓ Pause stops location updates and sending"

log "Backup is refused"
! adb shell dumpsys package "$PKG" | grep -E "^ +pkgFlags=" | grep -q ALLOW_BACKUP ||
  fail "$PKG is installed with ALLOW_BACKUP"
# The emulator's backup manager starts disabled ("Unable to run backup"); use the local transport.
adb shell bmgr enable true >/dev/null
adb shell bmgr transport com.android.localtransport/.LocalTransport >/dev/null
OUT=$(adb shell bmgr backupnow "$PKG" 2>&1 | tr -d '\r')
echo "$OUT"
echo "$OUT" | grep -qi "Backup is not allowed" || fail "backup of $PKG was not refused"
echo "✓ Backup refused"

TEST_STATUS=passed
echo ""
echo "✅ Android upgrade test passed ($BASE -> HEAD)"
