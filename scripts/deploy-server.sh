#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")/.."

fly deploy -c server/fly.toml --dockerfile server/Dockerfile .
