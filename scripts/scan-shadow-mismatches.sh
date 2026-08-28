#!/usr/bin/env bash
# Query Grafana Cloud Loki for recent shadow-write mismatches.
# Requires LOKI_URL, LOKI_USER, LOKI_API_KEY (same values the server pushes with).
#
# Usage: scan-shadow-mismatches.sh [limit] [since]
#   limit: max results (default 50)
#   since: how far back to look, as a Loki-style duration (default 24h).
#          Loki's own query_range default is 1h if start/end are omitted -
#          always pass an explicit window so an empty result actually means
#          "no mismatches", not "outside the default window".
set -euo pipefail

LIMIT="${1:-50}"
SINCE="${2:-24h}"

: "${LOKI_URL:?Set LOKI_URL (e.g. https://logs-prod-XXX.grafana.net)}"
: "${LOKI_USER:?Set LOKI_USER (the Grafana Cloud stack user id)}"
: "${LOKI_API_KEY:?Set LOKI_API_KEY}"

curl -sf -G "$LOKI_URL/loki/api/v1/query_range" \
    -u "$LOKI_USER:$LOKI_API_KEY" \
    --data-urlencode 'query={app="where-server", event="shadow_mismatch"}' \
    --data-urlencode "limit=$LIMIT" \
    --data-urlencode "since=$SINCE" \
    --data-urlencode "direction=backward" \
| jq '[.data.result[].values[]? | {timestamp: (.[0] | (tonumber / 1e9) | todate), line: (.[1] | fromjson)}]'
