# FCM Periodic Wakeup for Background Reliability

## Problem

On Samsung devices, the OS aggressively kills background processes through layers of battery
optimization beyond standard Android Doze. This affects even foreground services with
`foregroundServiceType="location"`. The result is location updates going silent for hours.
Google Maps is unaffected because its location sharing runs through Google Play Services
(`com.google.android.gms`), a privileged system process Samsung cannot restrict.

The existing `setExactAndAllowWhileIdle` alarm and WorkManager fallback are insufficient:
Samsung throttles both more aggressively than stock Android.

## Proposed Solution

Add Firebase Cloud Messaging (FCM) as a reliable wakeup source. The server sends a
content-free, high-priority FCM ping to all registered devices on a fixed cadence (e.g.
every 5 minutes). On receipt, the device wakes and polls its mailbox(es) as normal.

**Key design choice: unconditional broadcast, not per-message targeted push.**

The server does not store or use any mapping from FCM token to mailbox token. Every active
device receives every ping regardless of whether it has pending messages. This is intentional
for the privacy reasons described below.

## Privacy Analysis

### What Google learns
- A device is a Where client (from FCM token registration)
- The ping cadence — but not when location-sharing events occur, since pings are
  unconditional and not triggered by message arrival

### What the server learns

The server holds FCM tokens (one per device) with no explicit linkage to mailbox tokens.
However, a correlation is possible via IP: after sending a ping, the server can observe which
IP polls which `recv_token`, and correlate that IP back to the FCM token that was pinged
from the same IP. This is a two-hop inference requiring IP stability across the ping→poll
window, confounded by NAT and VPNs, but it is not zero.

This is a weaker form of the correlation already possible without FCM (the server can always
correlate polling IPs across requests). FCM adds a persistent device identifier — assigned
by Google, stable across sessions — to that correlation.

### Comparison to alternatives

| Approach | Google learns | Server can correlate |
|---|---|---|
| No FCM (current) | Nothing | IP → recv_token (per-request) |
| FCM, per-message push | Event timing + device ID | FCM token → recv_token (explicit) |
| FCM, periodic broadcast (proposed) | Device is a client | FCM token → recv_token (via IP, two-hop) |

### Comparison to Signal

Signal registers FCM tokens against account identifiers and sends targeted per-message
pushes. The proposed design is more privacy-preserving: no per-event timing signal to Google,
and no explicit server-side token linkage.

## Tradeoffs

**Against:**
- Google learns the set of Where devices (FCM token registration)
- Adds a hard dependency on Google infrastructure; breaks on de-Googled Android (GrapheneOS,
  CalyxOS). These users are likely to care most about the privacy properties above.
- Minor battery cost of unconditional wakeups on devices with no pending messages

**For:**
- Reliable background operation on Samsung (and other aggressive OEMs)
- The periodic wakeup cost is roughly equivalent to what the existing poll loop already
  incurs when the app is foregrounded
- Privacy cost is modest relative to alternatives and consistent with Signal's accepted tradeoff

## Open Questions

- Should FCM be opt-in, allowing privacy-sensitive users (including de-Googled device users)
  to accept reduced background reliability in exchange?
- What ping cadence balances battery vs. latency? 5 minutes matches the current stationary
  heartbeat interval.
- UnifiedPush as an alternative provider interface would allow self-hosted push and
  de-Googled device support, at significant implementation complexity.
