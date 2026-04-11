#!/bin/bash
cd "$(dirname "$0")/.."
./gradlew :shared:assembleSharedDebugXCFramework
