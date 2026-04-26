#!/bin/bash

# Fail on error
set -e

# The script runs from the directory it's in (ios/ci_scripts)
# Navigate to the project root
cd ../..
echo "Working directory: $(pwd)"

# 1. Create a dummy Local.xcconfig if it doesn't exist
if [ ! -f ios/Local.xcconfig ]; then
  echo "Creating dummy ios/Local.xcconfig"
  echo "// Created by ci_post_clone.sh" > ios/Local.xcconfig
fi

# 2. Setup Java 17
echo "Setting up Java 17..."

# Check if we are on ARM or Intel to find the correct Homebrew path
if [[ $(uname -m) == 'arm64' ]]; then
    BREW_PREFIX="/opt/homebrew"
else
    BREW_PREFIX="/usr/local"
fi

# Install openjdk@17 if not already present in Brew
if [ ! -d "$BREW_PREFIX/opt/openjdk@17" ]; then
    echo "openjdk@17 not found. Installing via Homebrew..."
    brew install --quiet openjdk@17
fi

# Configure environment to use the Brew-installed OpenJDK
export JAVA_HOME="$BREW_PREFIX/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$BREW_PREFIX/opt/openjdk@17/bin:$PATH"

echo "Using JAVA_HOME=$JAVA_HOME"

# Verify Java is actually working
if ! java -version; then
    echo "Error: Java is still not functional. Path check:"
    ls -R "$BREW_PREFIX/opt/openjdk@17" || echo "Directory not found"
    exit 1
fi

echo "Building Shared XCFramework..."
./gradlew :shared:assembleSharedDebugXCFramework :shared:assembleSharedReleaseXCFramework --no-daemon
