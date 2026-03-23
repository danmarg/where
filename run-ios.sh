#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

SIMULATOR_ID="AA956031-5F12-4266-A0B5-7C4932D0BF14"  # iPhone 17
DERIVED_DATA="/Volumes/Ext/DerivedData/Where"
APP_PATH="/Volumes/Ext/Build/Products/Debug-iphonesimulator/Where.app"

# Boot simulator if not already running
if ! xcrun simctl list devices | grep "$SIMULATOR_ID" | grep -q Booted; then
  echo "Booting iPhone 17 simulator..."
  xcrun simctl boot "$SIMULATOR_ID"
fi

# Open Simulator.app so the window appears
open -a Simulator

echo "Building..."
xcodebuild \
  -project ios/Where.xcodeproj \
  -scheme Where \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -configuration Debug \
  build 2>&1 | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"

echo "Installing..."
xcrun simctl install "$SIMULATOR_ID" "$APP_PATH"

echo "Launching..."
xcrun simctl launch "$SIMULATOR_ID" net.af0.where
