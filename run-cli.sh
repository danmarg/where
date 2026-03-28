#!/bin/bash
set -e
# Run the CLI tool using nix develop to ensure Java 21 is available.
if [ $# -eq 0 ]; then
  nix develop --command ./gradlew :cli:run --quiet
else
  nix develop --command ./gradlew :cli:run --quiet --args="$*"
fi
