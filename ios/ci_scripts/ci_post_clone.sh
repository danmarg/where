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

# 2. Install Java 17 via Homebrew
# Xcode Cloud comes with Homebrew pre-installed.
if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Installing openjdk@17 via Homebrew..."
    # Using --quiet to reduce log noise
    brew install --quiet openjdk@17
    
    # Add openjdk to the PATH
    export PATH="/usr/local/opt/openjdk@17/bin:$PATH"
    
    # Set JAVA_HOME
    export JAVA_HOME="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    echo "Installed Java and set JAVA_HOME to $JAVA_HOME"
else
    echo "Java is already installed."
    if [ -n "$(command -v /usr/libexec/java_home)" ]; then
        # Try to find Java 17, fallback to any available java
        export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)
        echo "Using JAVA_HOME=$JAVA_HOME"
    fi
fi

# Verify Java version
java -version

echo "Building Shared XCFramework..."
# We use --no-daemon for CI to avoid keeping background processes alive
./gradlew :shared:assembleSharedDebugXCFramework :shared:assembleSharedReleaseXCFramework --no-daemon
