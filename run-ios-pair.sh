#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")"

USE_NIX=false
for arg in "$@"; do
  [[ "$arg" == "--nix" ]] && USE_NIX=true
done

run() {
  if $USE_NIX; then
    nix develop --command "$@"
  else
    "$@"
  fi
}

# Path defaults — override via environment variables or local.properties.
APP_PATH="${APP_PATH:-ios/build/Build/Products/Debug-iphonesimulator/Where.app}"
if [ -f local.properties ]; then
  _PROP=$(grep "^APP_PATH=" local.properties | cut -d= -f2 | tr -d '[:space:]' || true)
  [ -n "$_PROP" ] && APP_PATH="$_PROP"
fi

# Dynamically fetch the iPhone 17 and iPhone 17 Pro simulator IDs
SIM1_ID=$(run xcrun simctl list devices | grep "iPhone 17" | grep -v "Pro" | grep -v "Max" | grep -v "e" | awk '{print $NF}' | tr -d '()')
SIM2_ID=$(run xcrun simctl list devices | grep "iPhone 17 Pro" | grep -v "Max" | awk '{print $NF}' | tr -d '()')

if [ -z "$SIM1_ID" ]; then
  echo "Error: iPhone 17 simulator not found."
  exit 1
fi

if [ -z "$SIM2_ID" ]; then
  echo "Error: iPhone 17 Pro simulator not found."
  exit 1
fi

boot_if_needed() {
  local udid=$1 name=$2
  if ! run xcrun simctl list devices | grep "$udid" | grep -q Booted; then
    echo "Booting $name..."
    run xcrun simctl boot "$udid"
  fi
}

boot_if_needed "$SIM1_ID" "iPhone 17"
boot_if_needed "$SIM2_ID" "iPhone 17 Pro"

open -a Simulator

echo "Building iOS app..."
if ! run xcodebuild \
  -project ios/Where.xcodeproj \
  -scheme Where \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -configuration Debug \
  -derivedDataPath ios/build \
  build 2>&1 | tee ios_build_pair.log | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"; then
  echo "Build command failed."
  exit 1
fi

if grep -q "BUILD FAILED" ios_build_pair.log; then
  echo "Xcode build failed."
  exit 1
fi

echo "Installing on both simulators..."
run xcrun simctl install "$SIM1_ID" "$APP_PATH"
run xcrun simctl install "$SIM2_ID" "$APP_PATH"

echo "Launching on both simulators..."
run xcrun simctl launch "$SIM1_ID" net.af0.where
run xcrun simctl launch "$SIM2_ID" net.af0.where

echo ""
echo "Tip: set different locations with:"
echo "  xcrun simctl location $SIM1_ID set 37.7749,-122.4194  # San Francisco"
echo "  xcrun simctl location $SIM2_ID set 37.3352,-122.0096  # Cupertino"
