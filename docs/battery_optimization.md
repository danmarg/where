# Location Updates and Battery Optimization Design

This document details the design, optimization strategies, and battery tradeoffs for background/foreground location updates and network synchronization in the Where application.

---

## 1. Core Architecture Constraints & Challenges

Where uses a **zero-knowledge, end-to-end encrypted (E2EE)** model. This architecture introduces specific constraints for background operations and synchronization:
1. **Zero-Knowledge Routing:** The server does not know who is friends with whom, nor does it store a social graph. Data is sent to and retrieved from anonymous mailboxes.
2. **Polling-Based Model:** Because the server cannot associate users without metadata leakage, the app relies on client-side polling to retrieve updates from mailboxes. 
3. **No-Push Default:** Push notification mechanisms (like Firebase Cloud Messaging / APNs) are intentionally avoided in the core sync engine to preserve the zero-knowledge design and avoid client-token metadata correlation on central servers.

This polling-and-GPS combination is inherently resource-intensive. Battery optimization requires balancing real-time responsiveness for active users with aggressive power-saving measures when the app is backgrounded or stationary.

---

## 2. Power Consumption Vector Analysis

Battery consumption in mobile location-sharing apps is driven by two main hardware subsystems: the **Network Radio (Cellular/Wi-Fi)** and the **GPS/GNSS Receiver**.

```
+-------------------------------------------------------------+
|                     TOTAL BATTERY DRAIN                     |
+------------------------------+------------------------------+
                               |
            +------------------+------------------+
            |                                     |
            v                                     v
+-----------------------+             +-----------------------+
|  Cellular/Wi-Fi Radio |             |       GPS / GNSS      |
|  - Radio State Tail   |             |  - TTFF (Cold Start)  |
|  - Frequency of Polls |             |  - High Peak Current  |
+-----------------------+             +-----------------------+
```

### 2.1 Network Radio State Machine Tradeoffs
Mobile radios transition through several power states when performing network requests:

* **Active/High Power (DCH/HS):** The radio is actively transmitting or receiving data. This consumes the most power.
* **Tail State (FACH/Idle-hold):** After a transmission ends, the radio remains in an intermediate power state for **5 to 15 seconds** waiting for potential subsequent packets. This prevents latency but drains significant battery if short network requests are spaced just far enough apart.
* **Idle/Sleep:** The lowest power state.

#### The 5-Minute Background Poll Tradeoff
* **Why 5 minutes?** A 5-minute interval (300 seconds) allows the radio to fall completely back to the idle sleep state between polls. Over 24 hours, this results in **288 wakeups**. Assuming a 10-second tail state per wakeup, the radio is in a powered state for ~48 minutes cumulative. This represents a minor, predictable battery impact.
* **Why not faster (e.g., 1 minute)?** Spacing requests at 1 minute means the radio stays in its high/medium power tail state almost continuously, leading to catastrophic battery drain (up to 5-10x higher radio power consumption).
* **Maintenance Mode (30 minutes):** When location sharing is globally paused, the polling interval is increased to **30 minutes** (48 wakeups/day) to maintain cryptographic ratchet synchronization (handling epoch rotations and key-exchange acknowledgments) while reducing network overhead to a negligible minimum.

### 2.2 GPS/GNSS Hardware Tradeoffs
The GPS receiver is the single most expensive sensor on a mobile device:
* **Cold Starts & TTFF (Time to First Fix):** Searching for satellites can draw upwards of 80–120 mA for 10–30 seconds.
* **Continuous Tracking:** Once a lock is acquired, maintaining it still consumes substantial current (~40-60 mA).
* **Drift & Noise:** GPS signals bounce off buildings (multipath interference) and drift when stationary. If the app reacts to every minor noise fluctuation as "movement," it will keep the radio active transmitting junk data.

---

## 3. Location and Synchronization Protocol

To optimize these hardware limits, Where uses a structured approach to location capture and transmission.

```
       [Location Sensor Fix]
                 │
                 ▼
     [Movement Radius Filter]  ───(Distance < 200m)───► [Discard Fix]
                 │
                 ▼ (Distance >= 200m)
      [Software Send Throttle] ───(Time < 30s)────────► [Cache Fix for Next Send]
                 │
                 ▼ (Time >= 30s)
      [Encrypt & POST to Server]
```

### 3.1 Movement Radius Filter (200 meters)
We enforce a **200-meter movement threshold** on both iOS and Android before considering a device to be "moving."
* **Drift Suppression:** A 200m threshold filters out typical GPS drift when a user is indoors, preventing false "moving" triggers.
* **Battery Conservation:** By ignoring movements less than 200m, we avoid waking up the E2EE encryption engine and the cellular radio for negligible position changes.

### 3.2 Software Send Throttle (30 seconds)
Even if the operating system delivers location updates at high frequencies (e.g., during driving), the app limits outbound network transmissions to **once every 30 seconds** (`MIN_SEND_INTERVAL_MS`).
* Updates received within the 30-second window overwrite the local cached location and are queued.
* At the end of the throttle period, the latest coordinates are encrypted and sent. This ensures friends see the most recent position without flooding the network.

### 3.3 Dynamic Sensor Request Intervals (Android Fused Location)
The Fused Location Provider on Android requests updates using a time interval. We split this interval based on the application's lifecycle state:

1. **Foreground & Moving (10 seconds):**
   When the user is actively viewing the map, we request GPS fixes every **10 seconds** to ensure their local blue dot moves smoothly.
2. **Background & Moving (30 seconds):**
   When the app is in the background, the user cannot see their local position. Therefore, requesting GPS every 10s is redundant. We increase the sensor request interval to **30 seconds** to match the software send throttle, saving hardware cycles.

---

## 4. Stationary & Motion States

To prevent continuous GPS consumption when a device is stationary, both platforms employ motion detection to transition between active tracking and sleep.

### 4.1 Android: Activity Recognition & Geofencing
Android utilizes Google Play Services Activity Recognition and Geofencing:

* **Still Detection:** Using the `ActivityRecognitionClient`, the app detects when the user enters the `STILL` state (stationary for >60s).
* **Passive Mode & Geofencing:** Upon entering the `STILL` state:
  1. The app registers a circular **200m Geofence** centered at the current location.
  2. The active GPS request priority is downgraded to `Priority.PRIORITY_PASSIVE` (meaning GPS is turned off unless another app on the system requests a fix).
* **Heartbeat Send:** Every 5 minutes, the background poll loop wakes up. Instead of querying the GPS hardware (which would wake the satellite receiver), it **reuses the last cached location** to send a heartbeat update to the server.
* **Geofence Exit:** When the user moves beyond the 200m geofence, the OS triggers a geofence exit transition. The app immediately removes the geofence, restores `Priority.PRIORITY_HIGH_ACCURACY`, and increases GPS requests to the active moving rate (10s foreground / 30s background).

### 4.2 iOS: Motion Activity, Distance Filters, and BGAppRefreshTask
iOS leverages CoreLocation's native power management combined with CoreMotion and BackgroundTasks:

* **Stationary Detection:** The app queries `CMMotionActivityManager` to check if the user has been stationary for the last 60 seconds.
* **Distance Filters:** The core location manager uses a `distanceFilter` of **200m**. In the background, iOS automatically keeps the GPS hardware in a low-power passive monitoring mode, waking up the app only when cellular tower triangulation or Wi-Fi signals indicate the user has crossed the 200m boundary.
* **Heartbeat & Location Reuse:** Similar to Android, if the device is marked stationary by CoreMotion, the 5-minute background synchronization loop reuses the cached coordinates, avoiding active GPS satellite searches.
* **Background App Refresh Task (BGAppRefreshTask):** While `CLBackgroundActivitySession` keeps the app active when moving, the iOS system can still suspend or terminate the app under memory pressure or prolonged inactivity. To ensure the 5-minute background poll doesn't stall indefinitely, the app registers a `BGAppRefreshTaskRequest` (`net.af0.where.heartbeat`) to trigger system-scheduled fallback wakes (every 15–30 minutes).
* **0 Friends Optimization for BGTask:** If the user has 0 friends and 0 pending invites, scheduling the next `BGAppRefreshTask` is skipped. This prevents unnecessary CPU and radio wake-ups when the app is completely idle. The task scheduling resumes immediately once the user adds a friend or creates/joins an invite.

---

## 5. Zero-Friend and Zero-Sharing Optimization

An important optimization is handling the idle state when tracking is unnecessary. If a user is not actively sharing location with anyone, background resources should be minimized.

### 5.1 Idle Criteria
The app enters the idle state if **any** of the following conditions are met:
1. The user has **0 friends** AND **0 pending invites**.
2. Location sharing is **globally disabled** in the user's settings.

### 5.2 Behavior in Idle State (0 friends + 0 pending invites)

**iOS:**
* **Stop GPS:** `LocationManager.updateRegistration()` checks `LocationSyncService.shared.friends` and `pendingInvites` in addition to the sharing flag. If both lists are empty, `stopUpdating()` is called regardless of sharing state, allowing the GPS hardware to sleep.
* **Poll Interval:** `targetPollInterval()` returns the 30-minute maintenance interval when both lists are empty, keeping the session alive for future key-exchange work without burning GPS or radio.
* **Trigger:** `removeFriend()` and `clearInvite()` call `locationProvider.sharingStateChanged()` after updating state, ensuring `updateRegistration()` is re-evaluated immediately when the last relationship is removed.

**Android:**
* **Stop GPS:** `ensureLocationRegistration()` deregisters both the active and passive FusedLocation callbacks when `locationSource.friends` and `locationSource.allPendingInvites` are both empty. The service subscribes to these flows in `onCreate()` so GPS is dropped the moment the last relationship is removed.
* **Stop Foreground Service:** After each `doPoll()` cycle, `pollLoop()` checks the relationship lists. If both are empty, the service calls `stopSelf()` and exits — eliminating the persistent notification and foreground-service wakelock entirely.
* **Cancel WorkManager Keepalive:** `manageForegroundService()` in `LocationViewModel` cancels the `LocationServiceRestartWorker` periodic work when there are no relationships, preventing the 15-minute heartbeat from restarting the service. The `LocationServiceRestartWorker` also performs its own guard so that any already-enqueued work item self-skips if invoked when both lists are empty.

### 5.3 State Recovery (Auto-Resume)
The app monitors database/UI updates to automatically resume operations:
* If the user receives/scans an invite, creates an invite, or accepts a friend, the app triggers `ensureLocationRegistration()` / `updateRegistration()`, immediately waking up GPS and resuming the normal background polling loops.
* On Android, `manageForegroundService()` re-enqueues the WorkManager keepalive as soon as a relationship exists, and the next foreground open starts the service again via the existing `startForegroundService()` path.

---

## 6. Summary of Standardized Intervals

To maintain parity and predictability across platforms, the following parameters are synchronized:

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| `RAPID_POLL_INTERVAL` | **2.0 seconds** | High-speed polling during QR scanning/pairing. |
| `FOREGROUND_POLL_INTERVAL` | **10.0 seconds** | Standard network polling when app is open. |
| `BACKGROUND_POLL_INTERVAL` | **300.0 seconds (5m)** | Normal background polling and heartbeat rate. |
| `MAINTENANCE_POLL_INTERVAL` | **1800.0 seconds (30m)** | Polling rate when sharing is paused or no friends exist. |
| `PUSH_THROTTLE_INTERVAL` | **30.0 seconds** | Software limit for sending location updates. |
| `MOVEMENT_RADIUS_THRESHOLD` | **200.0 meters** | Distance filter to trigger a movement update. |
| `STATIONARY_GEOFENCE_RADIUS` | **200.0 meters** | Android geofence size to detect stationary exit. |
| `GPS_INTERVAL_FOREGROUND` | **10.0 seconds** | GPS request interval on Android in foreground. |
| `GPS_INTERVAL_BACKGROUND` | **30.0 seconds** | GPS request interval on Android in background. |
