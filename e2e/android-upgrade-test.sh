#!/usr/bin/env bash
# Android upgrade E2E: the last release's app pairs with the CLI through an invite link and
# exchanges locations, is upgraded in place (adb install -r) to this checkout's app, and must
# keep its friend and session. Then, on the upgraded app: sends and a pairing made during a server
# outage go through once it's back; it recovers from its process being killed, from deep Doze
# (without a battery exemption) and from a reboot without being opened (unless E2E_QUICK); opening an accepted
# invite again doesn't pair twice; pausing stops location updates and sends; backup is refused;
# per-friend pause and friend removal affect only that friend; with location permission revoked
# nothing is sent, and sharing resumes once it's granted again.
#
# Uses minified release builds of the fdroid flavor: it reads the platform GPS/network providers,
# which emulator test providers drive directly. The gms flavor's fused location runs inside Play
# services, which on a fresh emulator restarts itself mid-test and drops or never delivers test
# fixes. Upgrade-relevant code (shared, database, E2EE, invites, pause, backup rules) is the same
# in both flavors. Runs against a booted emulator (google_apis image, which has su).
# Usage: e2e/android-upgrade-test.sh
# Env: BASE_REF (default: latest v* tag), BASELINE_APK / HEAD_APK (prebuilt, skips a build),
#      ABI (default: x86_64), E2E_BUILD_ONLY=1 (build the APKs and stop, e.g. before booting
#      the emulator), E2E_QUICK=1 (skip the Doze and reboot scenarios).
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

cli_bob_got_lat_prefix() {
  cli bob poll --once | grep "Location from" | tee -a "$WORK/cli-bob-locations.txt" | grep -q ": $1"
}

# cli_got_nothing <state>: <state>'s CLI has no location waiting.
cli_got_nothing() { ! cli "$1" poll --once | grep -q "Location from"; }

feed_and_check_bob() {
  friends_keepalive
  FEED_N=$((FEED_N + 1))
  set_location "$1" "$([ $((FEED_N % 2)) = 0 ] && echo 13.42 || echo 13.43)"
  sleep 3
  cli_bob_got_lat_prefix "$1"
}

cli_bob_established() { cli bob poll --once | grep -q "Established session"; }

# <lat> without a trailing zero: the CLI prints 52.70 as "52.7".
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

# While the server is down: move, without checking (the CLI can't reach it either).
move_without_check() {
  FEED_N=$((FEED_N + 1))
  set_location "$1" "$([ $((FEED_N % 2)) = 0 ] && echo 13.42 || echo 13.43)"
  sleep 4
}

# A live friend's app sends keepalives on its own; the CLI only when told to. Without them the app
# treats a CLI friend as unresponsive and sends to it at most once per 5 min
# (UNRESPONSIVE_SEND_INTERVAL_SECONDS). At most once a minute; ignored for a removed friend.
KEEPALIVE_AT=0
BOB_INVITED=
friends_keepalive() {
  [ $SECONDS -lt $KEEPALIVE_AT ] && return 0
  local f out rc
  for f in alice ${BOB_INVITED:+bob}; do
    out=$(cli "$f" keepalive) && rc=0 || rc=$?
    echo "$(date +%T) $f (exit $rc): ${out//$'\n'/ }" >>"$WORK/cli-keepalive.log"
  done
  KEEPALIVE_AT=$((SECONDS + 60))
  return 0
}

feed_and_check_cli() {
  friends_keepalive
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

app_pid() { adb shell pidof "$PKG" | tr -d '\r'; }

app_restarted_since() { local p; p=$(app_pid); [ -n "$p" ] && [ "$p" != "$1" ]; }

# feed_until_cli_gets <seconds> <lat>: keep moving around <lat> until the CLI receives it.
feed_until_cli_gets() { retry "$1" feed_and_check_cli "$2"; }

# tap_in_friend_row <friend> <button>: opens the friends sheet and taps <button> (its content
# description, e.g. "Pause") in <friend>'s row. Leaves the sheet (or the menu the button opens)
# up.
tap_in_friend_row() {
  local xy
  maestro_flow android/open-friends.yaml
  maestro ${MAESTRO_DEVICE:+--device "$MAESTRO_DEVICE"} hierarchy >"$WORK/friends-hierarchy.json" ||
    fail "couldn't dump the friends sheet"
  xy=$(python3 "$E2E_DIR/friend-row-button.py" "$WORK/friends-hierarchy.json" "$1" "$2") ||
    fail "no $2 button for $1 in the friends sheet"
  # shellcheck disable=SC2086 # "x y"
  adb shell input tap $xy
  sleep 2
}

# toggle_friend_pause <friend> <Pause|Resume>
toggle_friend_pause() {
  tap_in_friend_row "$1" "$2"
  maestro_flow android/close-friends.yaml
}

remove_friend() {
  tap_in_friend_row "$1" "More options"
  maestro_flow android/remove-friend.yaml
  maestro_flow android/close-friends.yaml
}

setup_test_location() {
  adb shell appops set com.android.shell android:mock_location allow
  adb shell cmd location providers add-test-provider gps --requiresSatellite --supportsAltitude
  adb shell cmd location providers add-test-provider network --requiresNetwork --supportsAltitude
  adb shell cmd location providers set-test-provider-enabled gps true
  adb shell cmd location providers set-test-provider-enabled network true
}

start_logcat() {
  adb logcat -v time >> "$WORK/logcat.txt" 2>/dev/null &
  LOGCAT_PID=$!
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
setup_test_location
adb logcat -c
# Streamed to a file: Maestro clears the device log buffer when it starts a flow.
start_logcat
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

# The server goes dark (timeouts, state kept) while the app moves and accepts a new invite from a
# second CLI identity. Once it's back, both the queued key exchange and new positions get through.
log "A server outage: sends and a pairing made during it go through once it's back"
URL2=$(cli bob invite Bob --no-wait | grep "Invite URL:" | awk '{print $3}')
BOB_INVITED=1
[ -n "$URL2" ] || fail "CLI could not create an invite"
cli alice poll --once >/dev/null
freeze_server
open_link "$URL2"
maestro_flow android/name-friend.yaml
retry 30 friend_count_is 2 || {
  maestro_flow android/tap-add-if-shown.yaml # the tap didn't register
  retry 30 friend_count_is 2 || fail "the app did not record the new friend during the outage"
}
for _ in $(seq 1 8); do move_without_check 52.71; done
thaw_server
retry 240 cli_bob_established || fail "the key exchange queued during the outage never reached the CLI"
feed_until_cli_gets 240 52.71 || fail "no location got through after the outage"
echo "✓ Outage: queued pairing and new positions delivered afterwards"

log "A killed app process comes back and keeps sending"
PID=$(app_pid)
adb shell su 0 kill -9 "$PID"
cli alice poll --once >/dev/null
retry 180 app_restarted_since "$PID" || fail "the app was not restarted after its process was killed"
feed_until_cli_gets 240 52.72 || fail "no location after the app process was killed"
echo "✓ Process death: restarted and sending"

# Doze and reboot take ~5 min each; E2E_QUICK skips them for quicker runs on PRs and on demand.
if [ -n "$E2E_QUICK" ]; then
  log "Skipping Doze and reboot (E2E_QUICK)"
else
  # Not exempt from battery optimization, as for a user who declines the app's prompt.
  log "Deep Doze, not battery-exempt: heartbeats still get through"
  adb shell dumpsys deviceidle whitelist "-$PKG" >/dev/null
  adb shell dumpsys battery unplug
  adb shell input keyevent KEYCODE_SLEEP
  adb shell dumpsys deviceidle force-idle
  # Count only sends made well inside idle (not one already under way as it began), and only if
  # the device is still idle when the CLI sees it.
  sleep 60
  cli alice poll --once >/dev/null
  DOZE_OK=0
  retry 900 cli_got_any_location && [ "$(adb shell dumpsys deviceidle get deep | tr -d '\r')" = IDLE ] && DOZE_OK=1
  adb shell dumpsys deviceidle unforce
  adb shell dumpsys battery reset
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
  [ $DOZE_OK = 1 ] || fail "no heartbeat reached the CLI in 15 min of deep Doze"
  echo "✓ Doze: heartbeat delivered while idle"

  log "After a reboot, sharing resumes without opening the app"
  kill "$LOGCAT_PID" 2>/dev/null
  adb reboot
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
  start_logcat
  setup_test_location
  adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null # shell-added exemptions don't survive a reboot
  adb shell wm dismiss-keyguard
  cli alice poll --once >/dev/null
  feed_until_cli_gets 300 52.74 || fail "no location after a reboot"
  echo "✓ Reboot: sharing resumed in the background"
fi

rejections() { grep -c "invite already accepted" "$WORK/logcat.txt" || true; }
rejected_since() { [ "$(rejections)" -gt "$1" ]; }

log "Opening an accepted invite again doesn't pair twice"
n=$(rejections)
open_link "$URL2"
retry 20 rejected_since "$n" || fail "the app didn't reject the replayed invite"
maestro_flow android/invite-already-used.yaml
friend_count_is 2 || fail "re-opening an accepted invite paired again"
# Accepted by the baseline app, before the upgraded one tracked accepted invites.
n=$(rejections)
open_link "$URL"
retry 20 rejected_since "$n" || fail "the app didn't reject the invite accepted before the upgrade"
maestro_flow android/invite-already-used.yaml
friend_count_is 2 || fail "re-opening an invite accepted before the upgrade paired again"
echo "✓ Accepted invites rejected on replay, including one accepted before the upgrade"

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

log "Sharing with one friend can be paused while the other keeps receiving"
maestro_flow android/resume.yaml
toggle_friend_pause Bob Pause
cli alice poll --once >/dev/null
cli bob poll --once >/dev/null
feed_until_cli_gets 240 52.77 || fail "the unpaused friend stopped receiving"
cli_got_nothing bob || fail "a location was sent to the paused friend"
echo "✓ Per-friend pause"

log "A removed friend stops receiving; the others don't"
toggle_friend_pause Bob Resume
remove_friend Alice
retry 30 friend_count_is 1 || fail "the friend wasn't removed"
cli alice poll --once >/dev/null
cli bob poll --once >/dev/null
retry 240 feed_and_check_bob 52.78 || fail "the remaining friend stopped receiving"
cli_got_nothing alice || fail "a location was sent to the removed friend"
echo "✓ Friend removal"

# Revoking a runtime permission kills the app's process.
log "With location permission revoked nothing is sent; once granted again, sharing resumes"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm revoke "$PKG" "android.permission.$p"
done
cli bob poll --once >/dev/null
for _ in $(seq 1 8); do move_without_check 52.79; done
cli_got_nothing bob || fail "a location was sent without location permission"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant "$PKG" "android.permission.$p"
done
maestro_flow android/launch.yaml
retry 240 feed_and_check_bob 52.81 || fail "sharing didn't resume after location permission was granted again"
echo "✓ Permission revoked and restored"

TEST_STATUS=passed
echo ""
echo "✅ Android upgrade test passed ($BASE -> HEAD)"
