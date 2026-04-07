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
- **LocationSyncService**: Manages an asynchronous poll loop. It uses `AsyncStream` for immediate signaling and `UIBackgroundTaskIdentifier` to ensure network tasks complete before the OS suspends the process.

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

Both platforms implement adaptive polling and sending to conserve battery while maintaining responsiveness:

- **Movement Throttle**: 15 seconds. Location is shared if the user has moved >10m and the last send was >15s ago.
- **Heartbeat Throttle**: 5 minutes. If stationary, a "last seen" update is sent every 5 minutes.
- **Poll Interval**: 60 seconds (Default) / 2 seconds (Rapid). Rapid polling is active during pairing or for 5 minutes after a manual trigger.
