#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Path defaults — override via environment variables or local.properties.
APP_PATH="${APP_PATH:-/Volumes/Ext/Build/Products/Debug-iphonesimulator/Where.app}"
if [ -f local.properties ]; then
  _PROP=$(grep "^APP_PATH=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')
  [ -n "$_PROP" ] && APP_PATH="$_PROP"
fi

SIM1_ID="AA956031-5F12-4266-A0B5-7C4932D0BF14"  # iPhone 17
SIM2_ID="11DCC254-3DC4-4CD1-AC51-DD9439F4FA64"  # iPhone 17 Pro

boot_if_needed() {
  local udid=$1 name=$2
  if ! xcrun simctl list devices | grep "$udid" | grep -q Booted; then
    echo "Booting $name..."
    xcrun simctl boot "$udid"
  fi
}

boot_if_needed "$SIM1_ID" "iPhone 17"
boot_if_needed "$SIM2_ID" "iPhone 17 Pro"

open -a Simulator

echo "Building..."
xcodebuild \
  -project ios/Where.xcodeproj \
  -scheme Where \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -configuration Debug \
  build 2>&1 | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"

echo "Installing on both simulators..."
xcrun simctl install "$SIM1_ID" "$APP_PATH"
xcrun simctl install "$SIM2_ID" "$APP_PATH"

echo "Launching on both simulators..."
xcrun simctl launch "$SIM1_ID" net.af0.where
xcrun simctl launch "$SIM2_ID" net.af0.where

echo ""
echo "Tip: set different locations with:"
echo "  xcrun simctl location $SIM1_ID set 37.7749,-122.4194  # San Francisco"
echo "  xcrun simctl location $SIM2_ID set 37.3352,-122.0096  # Cupertino"
