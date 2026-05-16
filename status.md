1. Polling Starvation (Resolved)
The primary bottleneck was in LocationClient.pollFriend, which processed mailbox
tokens sequentially. Under simulated network chaos, a "Long Poll" on a current
token would block the retrieval of transition messages waiting on older tokens,
causing a protocol deadlock.

Fix: I refactored the polling loop to use concurrent async tasks. Now, all known
tokens (current, previous, and retired) are polled in parallel, ensuring that
transition messages are picked up immediately regardless of network latency on
other tokens.
2. The "Token Pinning" Bug (Identified)
Logs from the E2eeChaosTest revealed that Alice was still stalling even with
concurrent polling. I identified a state-machine bug in Session.kt:

Root Cause: When a message triggers a new DH ratchet and simultaneously
acknowledges the sender's previous key, the session was failing to update
prevSendToken correctly.
Impact: This "pinned" Bob to an old routing token (463ee291) indefinitely. As
Alice ratcheted forward, this token was eventually dropped from her active
polling set, leading to a permanent synchronization stall for messages 2-9.
3. Header Key "Bricking" (Identified)
I discovered a second protocol-level issue causing the DecryptionException
errors seen in the logs:

Root Cause: The header key cache (skippedEpochHeaderKeys) was only being updated
if the protocol detected skipped sequence numbers. In chaotic networks, messages
from a previous epoch can arrive out-of-order even without sequence gaps.
Impact: When Alice ratcheted ahead, she discarded the header key for the
previous epoch too early. When Bob's delayed messages arrived, Alice could no
longer decrypt their headers to identify the DH epoch, resulting in the "all
encrypted messages failed to decrypt" state.
Next Steps
I am currently implementing the following fixes in Session.kt:

Robust Token Unpinning: Ensuring prevSendToken is correctly advanced to the most
recent acknowledged token during DH transitions.
Header Cache Hardening: Modifying the state machine to always preserve the
previous epoch's header key for a grace period, ensuring liveness for
out-of-order delivery.
Polling Optimization: Cleaning up the pollQueue in LocationClient to filter out
duplicate tokens generated during rapid transitions.
Once these are applied, I will rerun testExtremeChaos to verify full
convergence.


