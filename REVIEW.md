# PR#1 Review: User Location Management

## 1. Correctness & Logic Issues

### Server-side (Server.kt)
- **Session Hijacking/Overwrite**: The server uses `sessions[userId] = this` without checking if a session already exists for that `userId`. When a session is overwritten, the previous session remains open but is removed from the map. More critically, when *any* session for a `userId` closes, the `finally` block removes the `userId` from `sessions` and `locations` regardless of whether it was the active session or an old one.
- **ID Spoofing**: The server does not validate that the `userId` in `WsMessage.LocationUpdate` matches the `userId` associated with the WebSocket session. A client can report locations for any ID.
- **Broadcast Inefficiency**: `broadcastLocations` launches a new coroutine for every session on every update. While acceptable for a few users, this will lead to high coroutine churn under load.
- **State Loss**: All locations are in-memory; a server restart clears all pins. (Noted as intentional for v1 in `CLAUDE.md`).

### Shared Client (LocationSyncClient.kt)
- **Silent Send Failures**: `sendLocation` uses `session?.send(...)` inside a launched coroutine. If the session is null (e.g., during reconnection), the location update is silently dropped with no feedback or buffering.
- **Reconnection Logic**: The 3-second backoff is fixed. For a production app, exponential backoff is preferred to avoid "thundering herd" issues on server recovery.

### iOS Implementation (LocationSyncService.swift)
- **Task Leakage**: `connect()` does not check if an existing `receiveLoop` or `webSocketTask` is active. Repeated calls to `connect()` will spawn multiple concurrent receive loops.
- **Recursive Connection**: The error handler in `startReceiving` calls `self.connect()` after a delay. If multiple errors occur or if `connect()` is called from elsewhere, this can lead to multiple active connection attempts.
- **Strict Concurrency**: While `@MainActor` is used on the class, `MainActor.run` is used inside the `Task`. In Swift 6, since the `Task` is created in a `@MainActor` context, it should inherit the actor context unless specified otherwise, but the explicit `await MainActor.run` is safe (though redundant if the task is not detached).

## 2. Coding Standards (CLAUDE.md)

- **Kotlin/JVM Versions**: Follows the spec (JVM 17 for Android, JVM 21 for Server).
- **Dependency Management**: Correctly uses `libs.versions.toml`.
- **Architecture**: Separates concerns well between shared models and platform-specific services.
- **Missing Tests**: No unit tests were found in the PR for any of the modules.

## 3. Security Flags

- **Trusting Client Timestamps**: The server accepts the `timestamp` provided by the client in `UserLocation` without validation.
- **Unauthenticated WS**: Any client can connect and listen to all location updates for all users.

## 4. Recommended Fixes (Priority Order)

1. **Validate User ID** on server:
   ```kotlin
   if (msg is WsMessage.LocationUpdate && msg.location.userId == userId) { ... }
   ```
2. **Safe Session Removal**: Use `sessions.remove(userId, this)` and only remove from `locations` if it was the last session.
3. **Prevent Task Leakage** on iOS: Cancel existing tasks/loops before starting new ones in `connect()`.
4. **Add Tests**: At minimum, test the serialization/deserialization of `WsMessage` in the `shared` module.
