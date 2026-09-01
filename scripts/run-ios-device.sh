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

DEVICE_LIST=$(run xcrun devicectl list devices 2>/dev/null | grep -E '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}')

if [ -n "${DEVICE_ID:-}" ]; then
  : # explicit override wins
elif [ -n "$DEVICE_NAME" ]; then
  DEVICE_ID=$(echo "$DEVICE_LIST" | awk -v name="$DEVICE_NAME" '$1 == name {print $3}' | head -1)
  if [ -z "$DEVICE_ID" ]; then
    echo "Error: no paired device named '$DEVICE_NAME' found."
    echo "$DEVICE_LIST"
    exit 1
  fi
else
  DEVICE_ID=$(echo "$DEVICE_LIST" | awk '{print $3}' | head -1)
  if [ -z "$DEVICE_ID" ]; then
    echo "Error: no paired iOS devices found. Connect/pair an iPhone first."
    exit 1
  fi
fi

echo "=== Target device: $DEVICE_ID ==="

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
