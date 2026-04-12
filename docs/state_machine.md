# Mobile State Machine Mechanics

This document describes the background synchronization and pairing state machine mechanics for the Where mobile applications (iOS and Android).

## 1. Background Synchronization

To ensure location updates are reliable even when the application is backgrounded or the UI is not active, both platforms centralize synchronization logic in persistent components.

### Android: Foreground Service Architecture
- **LocationService**: A Foreground Service that manages the core loop. It handles:
    - GPS tracking via `FusedLocationProviderClient`.
    - Peer update polling (mailbox checks).
    - Location sharing (heartbeats and movement-driven sends).
- **LocationRepository**: A reactive bridge (Singleton) using `StateFlow` and `Channel`. It allows the `LocationViewModel` to observe background state and signal immediate actions (like `wakePoll` or `triggerRapidPoll`).
- **Persistence**: The service runs as long as location sharing is enabled, ensuring synchronization survives Activity destruction.

### iOS: Location-Driven Background Execution
- **LocationManager**: Leverages `allowsBackgroundLocationUpdates`. Whenever the OS wakes the app to deliver a location update, the app also triggers a peer synchronization poll.
- **LocationSyncService**: Manages an asynchronous poll loop. It uses a `Timer` for interval-based scheduling and `UIBackgroundTaskIdentifier` to ensure network tasks complete before the OS suspends the process.
- **Foreground wake**: A `scenePhase` observer fires an immediate poll when the app becomes active, so the map is fresh when the user opens it.

## 2. Pairing State Machine

The pairing flow (Key Exchange) involves a transition from a discovery state to an established session.

### Roles
- **Alice (Inviter)**: Generates a QR code and polls a discovery mailbox for a response.
- **Bob (Joiner)**: Scans the QR code and posts an initial handshake to the discovery mailbox.

### Transitions
1. **Idle**: `InviteState.None`.
2. **Inviting (Alice)**: Tapping "Invite" transitions to `InviteState.Pending`. Alice shows the QR code and the background service begins "Rapid Polling" (2s interval).
3. **Scanning (Bob)**: Bob scans the QR, enters Alice's name, and posts `KeyExchangeInit`.
4. **Response Found (Alice)**: The background service polls the discovery token and finds Bob's response.
    - **Trigger**: Alice's side sets `pendingInitPayload`.
    - **UI**: The `InviteState` transitions to `None` to dismiss the QR sheet, and the "Name this friend" alert appears.
5. **Confirmation**: Alice enters Bob's name and confirms.
    - **Finalization**: `e2eeStore.processKeyExchangeInit` derives the session and clears the invite from persistent storage.

## 3. Throttling and Efficiency

Both platforms implement adaptive polling and sending to conserve battery while maintaining responsiveness.

### Poll intervals

| State | Interval | Reason |
|---|---|---|
| Rapid (pairing) | 1–2 s | Fast key-exchange response |
| Foreground | 10–60 s | User is watching the map |
| Background, sharing on | 5 min | Matches heartbeat; keeps friend locations fresh |
| Background, sharing off | 30 min | Maintenance-only: process Ratchet Acks so Alice's session doesn't get stuck |

The timer is adjusted at the end of each `firePoll` / `pollLoop` iteration to match the current state.

### Send throttles
- **Movement throttle**: 15 seconds. Location is shared if the user has moved >10 m and the last send was >15 s ago.
- **Heartbeat throttle**: 5 minutes. If stationary, a "last seen" update is sent every 5 minutes. Only fires when sharing is on.

## 4. PCS Staleness Timeout (issue #38)

The standard Double Ratchet protocol requires both peers to continually send messages to each other to rotate DH keys. If Alice continues sending locations to Bob indefinitely, but Bob never replies, Alice remains stuck on the same symmetric chain. This provides forward secrecy, but loses post-compromise security (PCS).

To make this failure visible and enforce a bound on key reuse:

1. **`lastRecvTs`** is stored on every `FriendEntry`, initialized to the handshake timestamp and updated each time ANY valid message (Location or Keepalive) is decrypted.
2. **`FriendEntry.isStale`** returns `true` when `now − lastRecvTs > ACK_TIMEOUT_SECONDS` (7 days).
3. **`LocationClient.sendLocation`** skips friends who are stale, stopping location sharing to that specific friend.
4. **UI warning**: both apps display a warning next to any friend whose session is stale ("Friend inactive — sharing paused").

### Why 7 days?
Seven days is generous enough that brief phone-off or airplane-mode periods don't trigger the warning, but short enough that a genuinely broken session (uninstalled app, lost key, or peer who no longer cares) is eventually surfaced to Alice, preventing indefinite symmetric chain progression.

### Maintenance poll (sharing off)
To keep the Double Ratchet flowing even when the user turns off location sharing, the background poll continues at a reduced 30-minute interval. This poll does not send locations (the heartbeat is gated on `isSharingLocation`), but if a new DH key is received during the poll, it automatically posts a `Keepalive` message in response. This ensures that the peer's DH Ratchet advances and their PCS is maintained.
