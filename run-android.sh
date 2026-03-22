#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

export ANDROID_HOME=/Volumes/Ext/android-sdk
export ANDROID_SDK_ROOT=/Volumes/Ext/android-sdk
export ANDROID_AVD_HOME=/Volumes/Ext/android-avd
export PATH=/Volumes/Ext/android-sdk/emulator:/Volumes/Ext/android-sdk/platform-tools:/Volumes/Ext/android-sdk/cmdline-tools/latest/bin:$PATH

# Read ANDROID_ADB_HOST from local.properties if set
ADB_HOST=$(grep "^ANDROID_ADB_HOST=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')

if [ -n "$ADB_HOST" ]; then
  ADB="adb -s $ADB_HOST:36869"
  adb connect "$ADB_HOST:36869" 2>/dev/null || true
else
  ADB="adb"
fi

# Check for any connected device (physical or emulator)
if $ADB devices | grep -q "device$"; then
  echo "Device connected."
else
  echo "No device found. Starting emulator..."
  emulator -avd pixel9 -no-audio -no-boot-anim -accel off &
  echo "Waiting for emulator to boot..."
  adb wait-for-device
  adb shell input keyevent 82  # unlock screen
  ADB="adb"
fi

echo "Building APK..."
nix develop --command ./gradlew :android:assembleDebug

echo "Installing APK..."
$ADB install -r /Volumes/Ext/Build/where/android/outputs/apk/debug/android-debug.apk

echo "Launching app..."
$ADB shell am start -n net.af0.where/.MainActivity
