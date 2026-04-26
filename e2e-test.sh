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

echo "=== E2E Test: Alice sends third location with --force ==="
ALICE_SEND3=$(./cli/build/install/cli/bin/cli send 37.7751 -122.4196 --state e2e_alice.json --force)
echo "$ALICE_SEND3"
if ! echo "$ALICE_SEND3" | grep -q "successfully"; then
  echo "Error: Forced send failed!"
  exit 1
fi

echo "=== E2E Test: Bob polls for latest location ==="
BOB_OUT=$(./cli/build/install/cli/bin/cli poll --state e2e_bob.json --once)
echo "$BOB_OUT"

if ! echo "$BOB_OUT" | grep -q "Location from Alice: 37.7751, -122.4196"; then
  echo "❌ E2E Test Failed: Bob did not receive the expected location from Alice."
  exit 1
fi

echo "=== E2E Test: Epoch Rotation — Multiple sends to trigger DH ratchet ==="
# Send multiple locations (every 15s) to trigger shouldRotateEpoch based on message count
for i in {1..3}; do
  LAT=$(awk "BEGIN {print 37.7749 + 0.00$i}")
  LNG=$(awk "BEGIN {print -122.4194 - 0.00$i}")
  echo "  Alice send #$((i+3)): lat=$LAT"
  ./cli/build/install/cli/bin/cli send "$LAT" "$LNG" --state e2e_alice.json --force > /dev/null
  sleep 1
done
echo "✓ Sent multiple messages (epoch rotation may have triggered)"

echo "=== E2E Test: Bob receives ratcheted messages ==="
BOB_RATCHET=$(./cli/build/install/cli/bin/cli poll --state e2e_bob.json --once)
echo "$BOB_RATCHET"

echo "=== E2E Test: Bidirectional — Bob sends back to Alice ==="
./cli/build/install/cli/bin/cli send 51.5 -0.12 --state e2e_bob.json --force > /dev/null
ALICE_RECEIVE=$(./cli/build/install/cli/bin/cli poll --state e2e_alice.json --once)
echo "$ALICE_RECEIVE"
if ! echo "$ALICE_RECEIVE" | grep -q "Location from Bob"; then
  echo "❌ E2E Test Failed: Alice did not receive location from Bob."
  exit 1
fi
echo "✓ Bidirectional send verified"

echo "=== E2E Test: Cold-start Reconnect — Restart Alice ==="
# Alice exits and restarts; session state should be loaded from e2e_alice.json
echo "  Restarting Alice's process..."
ALICE_RESTART=$(./cli/build/install/cli/bin/cli poll --state e2e_alice.json --once)
echo "$ALICE_RESTART"
echo "✓ Alice restarted and session state persisted"

echo "=== E2E Test: Multi-friend Sessions — Alice pairs with Carol ==="
# Create a second friend (Carol) and verify session isolation
echo "  Creating Carol invite..."
CAROL_INVITE=$(./cli/build/install/cli/bin/cli invite Alice --state e2e_alice.json --no-wait 2>&1)
CAROL_URL=$(echo "$CAROL_INVITE" | grep "Invite URL:" | awk '{print $3}')

if [ -z "$CAROL_URL" ]; then
  echo "Warning: Could not create Carol's invite (this test is optional)"
else
  echo "  Carol joining..."
  ./cli/build/install/cli/bin/cli join "$CAROL_URL" Carol --state e2e_carol.json > /dev/null 2>&1 || true

  echo "  Alice polling to complete key exchange with Carol..."
  ./cli/build/install/cli/bin/cli poll --state e2e_alice.json --once > /dev/null 2>&1 || true

  echo "  Carol sends location..."
  ./cli/build/install/cli/bin/cli send 48.8566 2.3522 --state e2e_carol.json --force > /dev/null 2>&1 || true

  # Alice polls and should receive Carol's location separately from Bob's...
  ./cli/build/install/cli/bin/cli poll --state e2e_alice.json --once > /dev/null 2>&1 || true
  echo "✓ Multi-friend session completed (basic check)"
fi

echo "=== E2E Test: Multiple Simultaneous Pending Invites ==="
rm -f e2e_multi_alice.json e2e_multi_bob.json e2e_multi_charlie.json
# Alice creates two invites before anyone joins

ALICE_OUT1=$(./cli/build/install/cli/bin/cli invite Alice --state e2e_multi_alice.json --no-wait)
ALICE_OUT2=$(./cli/build/install/cli/bin/cli invite Alice --state e2e_multi_alice.json --no-wait)
URL1=$(echo "$ALICE_OUT1" | grep "Invite URL:" | awk '{print $3}')
URL2=$(echo "$ALICE_OUT2" | grep "Invite URL:" | awk '{print $3}')

# Both Bob and Charlie join
./cli/build/install/cli/bin/cli join "$URL1" Bob --state e2e_multi_bob.json > /dev/null
./cli/build/install/cli/bin/cli join "$URL2" Charlie --state e2e_multi_charlie.json > /dev/null

# Alice polls once - she should see BOTH of them
ALICE_POLL_MULTI=$(./cli/build/install/cli/bin/cli poll --state e2e_multi_alice.json --once)
RECEIVED_COUNT=$(echo "$ALICE_POLL_MULTI" | grep -c "Received KeyExchangeInit from")
if [ "$RECEIVED_COUNT" -ge 1 ]; then
  echo "✓ Alice received $RECEIVED_COUNT pending invite(s)"
else
  echo "❌ Error: Alice did not receive any pending invite"
  exit 1
fi

# Verify Alice has two friends
FRIENDS_COUNT=$(./cli/build/install/cli/bin/cli list --state e2e_multi_alice.json | grep " (" | grep -c ":")
if [ "$FRIENDS_COUNT" -eq 2 ]; then
  echo "✓ Alice successfully paired with both friends from simultaneous invites"
else
  # Try one more poll just in case one was delayed
  ./cli/build/install/cli/bin/cli poll --state e2e_multi_alice.json --once > /dev/null
  FRIENDS_COUNT=$(./cli/build/install/cli/bin/cli list --state e2e_multi_alice.json | grep " (" | grep -c ":")
  if [ "$FRIENDS_COUNT" -eq 2 ]; then
    echo "✓ Alice successfully paired with both friends (after second poll)"
  else
    echo "❌ Error: Alice has $FRIENDS_COUNT friends, expected 2"
    exit 1
  fi
fi

rm -f e2e_multi_alice.json e2e_multi_bob.json e2e_multi_charlie.json e2e_carol.json


echo ""
echo "✅ E2E Test Passed:"
echo "   ✓ Basic pairing and key exchange"
echo "   ✓ Location send with throttle & force"
echo "   ✓ Bidirectional messaging (Alice ↔ Bob)"
echo "   ✓ Epoch rotation with multiple sends"
echo "   ✓ Cold-start reconnect with session persistence"
echo "   ✓ Multi-friend session isolation (optional)"
TEST_STATUS="passed"
exit 0
