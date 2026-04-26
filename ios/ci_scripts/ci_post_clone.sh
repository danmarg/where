#!/bin/bash

# Exit on error, print commands for debugging
set -ex

# Navigate to project root
cd ../..
echo "Working directory: $(pwd)"

# 1. Create dummy Local.xcconfig
if [ ! -f ios/Local.xcconfig ]; then
  echo "Creating dummy ios/Local.xcconfig"
  echo "// Created by ci_post_clone.sh" > ios/Local.xcconfig
fi

# 2. Install and Setup Java 17
echo "Installing OpenJDK 17 via Homebrew..."
# brew install is usually fast if already cached, but we need to ensure it's there
brew install openjdk@17

# Detect Homebrew prefix based on architecture
if [[ $(uname -m) == 'arm64' ]]; then
    BREW_OPENJDK_PATH="/opt/homebrew/opt/openjdk@17"
else
    BREW_OPENJDK_PATH="/usr/local/opt/openjdk@17"
fi

# Set JAVA_HOME to the internal JDK structure
export JAVA_HOME="$BREW_OPENJDK_PATH/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

echo "JAVA_HOME is set to: $JAVA_HOME"

# Check if the binary exists and is executable
if [ -x "$JAVA_HOME/bin/java" ]; then
    echo "Java binary found at $JAVA_HOME/bin/java"
    "$JAVA_HOME/bin/java" -version
else
    echo "Error: Java binary NOT found at $JAVA_HOME/bin/java"
    find "$BREW_OPENJDK_PATH" -name java
    exit 1
fi

# 3. Build Shared XCFramework
echo "Building Shared XCFramework..."
# Pass JAVA_HOME explicitly to Gradle to ensure it doesn't use the system stub
./gradlew :shared:assembleSharedDebugXCFramework :shared:assembleSharedReleaseXCFramework \
    --no-daemon \
    -Dorg.gradle.java.home="$JAVA_HOME"
