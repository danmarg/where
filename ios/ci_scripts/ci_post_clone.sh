#!/bin/bash

# Fail on error
set -e

# The script runs from the directory it's in (ios/ci_scripts)
# Navigate to the project root
cd ../..
echo "Working directory: $(pwd)"

# 1. Create a dummy Local.xcconfig if it doesn't exist
# This is required by the Xcode project but ignored by git
if [ ! -f ios/Local.xcconfig ]; then
  echo "Creating dummy ios/Local.xcconfig"
  echo "// Created by ci_post_clone.sh" > ios/Local.xcconfig
fi

# 2. Build the XCFramework
# Xcode Cloud has Java installed. KMP usually needs Java 17+.
if [ -n "$(command -v /usr/libexec/java_home)" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

echo "Building Shared XCFramework..."
./gradlew :shared:assembleSharedDebugXCFramework :shared:assembleSharedReleaseXCFramework
