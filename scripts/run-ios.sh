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

# Path defaults — override via environment variables or local.properties.
APP_PATH="${APP_PATH:-ios/build/Build/Products/Debug-iphonesimulator/Where.app}"
if [ -f local.properties ]; then
  _PROP=$(grep "^APP_PATH=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)
  [ -n "$_PROP" ] && APP_PATH="$_PROP"
fi

# Dynamically fetch the iPhone 17 simulator ID (not Pro, Pro Max, or 17e)
SIMULATOR_ID=$(run xcrun simctl list devices | grep "iPhone 17 " | grep -v "Pro" | sed -E 's/.*\(([A-F0-9-]+)\).*/\1/' | head -1)

if [ -z "$SIMULATOR_ID" ]; then
  echo "Error: iPhone 17 simulator not found."
  exit 1
fi

# Boot simulator if not already running
if ! run xcrun simctl list devices | grep "$SIMULATOR_ID" | grep -q Booted; then
  echo "Booting iPhone 17 simulator..."
  run xcrun simctl boot "$SIMULATOR_ID"
fi

# Open Simulator.app so the window appears
open -a Simulator

echo "=== Building KMP shared framework ==="
run ./gradlew :shared:assembleSharedDebugXCFramework
echo "✓ KMP shared framework built"
echo ""

echo "=== Building iOS app ==="
if ! run xcodebuild \
  -project ios/Where.xcodeproj \
  -scheme Where \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -configuration Debug \
  -derivedDataPath ios/build \
  build 2>&1 | tee ios_build.log | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"; then
  echo "iOS build failed."
  exit 1
fi

if grep -q "BUILD FAILED" ios_build.log; then
  echo "iOS build failed."
  exit 1
fi
echo "✓ iOS app built"
echo ""

echo "=== Installing ==="
run xcrun simctl install "$SIMULATOR_ID" "$APP_PATH"
echo "✓ App installed"
echo ""

echo "=== Launching ==="
run xcrun simctl launch "$SIMULATOR_ID" net.af0.where
echo "✓ App launched"
