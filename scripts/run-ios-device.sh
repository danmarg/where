#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")/.."

# Load machine-specific environment if it exists
if [ -f .envrc ]; then
  source .envrc
fi

# Set TMPDIR early
export TMPDIR="${TMPDIR:-/tmp}"

run() {
  "$@"
}

# Device selection — override with DEVICE_NAME or DEVICE_ID, or pass as $1.
# Defaults to the first paired device if none specified.
DEVICE_NAME="${1:-${DEVICE_NAME:-}}"

# Physical, paired devices as "<udid><TAB><name>" lines, from devicectl's JSON output (the table
# output is awkward to parse: names contain spaces, hostnames can be blank, and device UDIDs come in
# both the UUID form and the shorter 00008103-001931E10A29A01E form).
DEVICES_JSON=$(mktemp "${TMPDIR}/devicectl.XXXXXX")
trap 'rm -f "$DEVICES_JSON"' EXIT
run xcrun devicectl list devices --json-output "$DEVICES_JSON" >/dev/null 2>&1 || true
DEVICE_LIST=$(python3 -I - "$DEVICES_JSON" <<'PY'
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    devices = []
for d in devices:
    hw = d.get("hardwareProperties", {})
    if hw.get("reality", "physical") == "simulated":
        continue
    if d.get("connectionProperties", {}).get("pairingState") != "paired":
        continue
    udid = hw.get("udid") or d.get("identifier", "")
    print("%s\t%s" % (udid, d.get("deviceProperties", {}).get("name", "")))
PY
)

if [ -n "${DEVICE_ID:-}" ]; then
  : # explicit override wins
elif [ -n "$DEVICE_NAME" ]; then
  DEVICE_ID=$(echo "$DEVICE_LIST" | awk -F'\t' -v name="$DEVICE_NAME" '$2 == name || $1 == name {print $1}' | head -1)
  if [ -z "$DEVICE_ID" ]; then
    echo "Error: no paired device named '$DEVICE_NAME' found."
    echo "Paired devices (udid, name):"
    echo "$DEVICE_LIST"
    exit 1
  fi
else
  DEVICE_ID=$(echo "$DEVICE_LIST" | awk -F'\t' '{print $1}' | head -1)
  if [ -z "$DEVICE_ID" ]; then
    echo "Error: no paired iOS devices found. Connect/pair an iPhone first."
    exit 1
  fi
fi

echo "=== Target device: $DEVICE_ID ==="

echo "=== Generating Xcode project ==="
(cd ios && run xcodegen generate)
echo "✓ Xcode project generated"
echo ""

echo "=== Building KMP shared framework ==="
run ./gradlew :shared:assembleSharedDebugXCFramework
echo "✓ KMP shared framework built"
echo ""

echo "=== Building iOS app ==="
if ! run xcodebuild \
  -project ios/Where.xcodeproj \
  -scheme Where \
  -destination "id=$DEVICE_ID" \
  -configuration Debug \
  -derivedDataPath ios/build \
  -allowProvisioningUpdates \
  build 2>&1 | tee ios_device_build.log | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"; then
  echo "iOS build failed."
  exit 1
fi

if grep -q "BUILD FAILED" ios_device_build.log; then
  echo "iOS build failed."
  exit 1
fi
echo "✓ iOS app built"
echo ""

APP_PATH="ios/build/Build/Products/Debug-iphoneos/Where.app"

echo "=== Installing ==="
run xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH"
echo "✓ App installed"
echo ""

echo "=== Launching ==="
run xcrun devicectl device process launch --device "$DEVICE_ID" net.af0.WhereApp
echo "✓ App launched"
