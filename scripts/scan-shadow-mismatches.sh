#!/usr/bin/env bash
# Scan the where_shadow_mismatches DynamoDB table for recent shadow-write mismatches.
set -euo pipefail

TABLE="${MISMATCH_TABLE:-where_shadow_mismatches}"
LIMIT="${1:-50}"

aws dynamodb query \
    --table-name "$TABLE" \
    --key-condition-expression "pk = :pk" \
    --expression-attribute-values '{":pk": {"S": "mismatches"}}' \
    --scan-index-forward false \
    --limit "$LIMIT" \
    --output json \
| jq '[.Items[] | {
    timestamp: (.sk.S | split("#")[0] | split(".")[0] | tonumber | todate),
    tokenHash: .tokenHash.S,
    primaryCount: (.primaryCount.N | tonumber),
    secondaryCount: (.secondaryCount.N | tonumber),
    onlyInPrimary: (.onlyInPrimary.N | tonumber),
    onlyInSecondary: (.onlyInSecondary.N | tonumber)
  }]'
