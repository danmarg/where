#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")"

# Load machine-specific environment if it exists
if [ -f .envrc ]; then
  source .envrc
elif [ -f .env ]; then
  source .env
fi

echo "=== E2E Test: Building components ==="
./gradlew :server:installDist :cli:installDist --quiet

echo "=== E2E Test: Starting Server ==="
./server/build/install/server/bin/server > e2e_server.log 2>&1 &
SERVER_PID=$!

TEST_STATUS="failed"

cleanup() {
  echo "=== E2E Test: Cleaning up ==="
  kill $SERVER_PID || true
  
  if [ "$TEST_STATUS" == "passed" ]; then
    echo "Test passed, removing logs and temporary state."
    rm -f e2e_alice.json e2e_bob.json e2e_alice.log e2e_server.log
  else
    echo "Test failed, keeping logs (e2e_alice.log, e2e_server.log) and state (e2e_alice.json, e2e_bob.json) for debugging."
  fi
  wait $SERVER_PID 2>/dev/null || true
}
trap cleanup EXIT

# Wait for server to boot using a curl loop (up to 30s)
echo "=== E2E Test: Waiting for server to start ==="
for i in {1..30}; do
  if curl -s http://localhost:8080/ >/dev/null; then
    break
  fi
  sleep 1
done
echo "Server is up."

echo "=== E2E Test: Alice creates invite ==="
# Since pendingInvite is now persisted, Alice can exit immediately.
ALICE_OUT=$(./cli/build/install/cli/bin/cli invite Alice --state e2e_alice.json --no-wait)
echo "$ALICE_OUT"
INVITE_URL=$(echo "$ALICE_OUT" | grep "Invite URL:" | awk '{print $3}')

if [ -z "$INVITE_URL" ]; then
  echo "Error: Failed to extract invite URL from Alice's output."
  exit 1
fi
echo "Alice Invite URL: $INVITE_URL"

echo "=== E2E Test: Bob joins using invite ==="
./cli/build/install/cli/bin/cli join "$INVITE_URL" Bob --state e2e_bob.json

echo "=== E2E Test: Alice polls to complete key exchange ==="
# Alice should now pick up Bob's KeyExchangeInit from the discovery token.
ALICE_POLL_OUT=$(./cli/build/install/cli/bin/cli poll --state e2e_alice.json --once)
echo "$ALICE_POLL_OUT"

echo "=== E2E Test: Alice sends first location ==="
./cli/build/install/cli/bin/cli send 37.7749 -122.4194 --state e2e_alice.json

echo "=== E2E Test: Alice sends second location immediately (should be throttled) ==="
ALICE_SEND2=$(./cli/build/install/cli/bin/cli send 37.7750 -122.4195 --state e2e_alice.json)
echo "$ALICE_SEND2"
if ! echo "$ALICE_SEND2" | grep -q "Throttled"; then
  echo "Error: Second send was NOT throttled!"
  exit 1
fi

echo "=== E2E Test: Alice sends third location with --force (Bug B/C fix) ==="
ALICE_SEND3=$(./cli/build/install/cli/bin/cli send 37.7751 -122.4196 --state e2e_alice.json --force)
echo "$ALICE_SEND3"
if ! echo "$ALICE_SEND3" | grep -q "successfully"; then
  echo "Error: Forced send failed!"
  exit 1
fi

echo "=== E2E Test: Bob polls for latest location ==="
BOB_OUT=$(./cli/build/install/cli/bin/cli poll --state e2e_bob.json --once)
echo "$BOB_OUT"

if echo "$BOB_OUT" | grep -q "Location from Alice: 37.7751, -122.4196"; then
  echo ""
  echo "✅ E2E Test Passed (including Throttle & Force verification)!"
  TEST_STATUS="passed"
  exit 0
else
  echo ""
  echo "❌ E2E Test Failed: Bob did not receive the expected location from Alice."
  TEST_STATUS="failed"
  exit 1
fi
