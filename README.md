# 📱 Kid Monitor App Suite

A comprehensive parental monitoring Android ecosystem designed for real-time protection, device oversight, and live environmental awareness.

The suite consists of three specialized applications:

1. **`parent-view/`** (Output: **`view-service-debug.apk`**)
   - Runs on the **parent's phone**.
   - Remote live screen viewing, live camera viewing (front/back), live microphone audio streaming, remote screenshot capture, daily app usage analytics, and multi-device management.
2. **`kid-monitor/`** (Output: **`system-service-debug.apk`**)
   - Full-featured monitoring app running as a silent background system service on the **child's phone**.
   - Captures screen, streams live front/rear camera feeds, live ambient microphone audio streaming, monitors foreground app usage, and automatically maintains 24/7 background persistence.
3. **`kid-camera-only/`** (Output: **`kid-camera-debug.apk`**)
   - Dedicated lightweight alternative app for the **child's phone**.
   - Strictly focused on remote camera access (front & rear) and ambient microphone audio streaming.
   - Ideal for scenarios where screen mirroring or usage stats permissions are not needed or not permitted. Compatible with the same Parent View app.

---

## ✨ Features

- 🔴 **Live Screen Mirroring** — Real-time, low-latency P2P screen streaming over WebRTC (720p@30fps).
- 📷 **Live Remote Camera** — Real-time P2P video stream from the child's front or rear camera.
- 🔄 **Remote Camera Switching** — Seamlessly toggle between front and back camera dynamically from the parent's device.
- 🎙️ **Live Ambient Audio (Microphone Streaming)** — Listen to live sound from the child's device environment. Microphone can be toggled ON/OFF on demand directly from the parent app.
- 🛡️ **24/7 Background Persistence & Watchdog** — Integrated `WatchdogReceiver` with `AlarmManager` wakeups and `BOOT_COMPLETED` auto-recovery ensures the service remains active across deep sleep, Doze mode, and device reboots.
- 🟢 **Accurate Online/Offline Status Indicator** — Real-time status reporting that correctly reflects the child's connectivity without getting stuck offline.
- 📊 **App Usage Insights** — View comprehensive statistics of which apps the child uses and daily usage duration.
- 📸 **Instant Screenshot** — Capture and save stills from active live streams directly to parent storage.
- 📱 **Multi-Device Support** — Pair and manage multiple children's devices under a single parent account.
- 🔕 **Stealth Operation** — Camera and audio streaming services run as unobtrusive background foreground services (`IMPORTANCE_MIN`, `VISIBILITY_SECRET`).

---

## 🏗️ Architecture

```
Kid Phone (kid-monitor or kid-camera-only)       Supabase Realtime (Signaling)        Parent Phone (parent-view)
─────────────────────────────────────────       ─────────────────────────────        ──────────────────────────
Screen capture (WebRTC H.264/VP8)          ←─── WebRTC SDP/ICE exchange (P2P) ───►  SurfaceViewRenderer (Screen)
Camera stream  (WebRTC H.264/VP8)          ←─── WebRTC SDP/ICE exchange (P2P) ───►  SurfaceViewRenderer (Camera)
Microphone     (WebRTC Opus Audio)         ←─── WebRTC Audio Track (P2P)      ───►  AudioTrack / Speakerphone
Heartbeat / Status (Supabase/Firebase)     ──── Periodic Ping / Online state  ───►  Device List Status (Live)
App usage stats (Firebase RTDB)            ──── Sync Usage History            ───►  App Usage Activity
AlarmManager Watchdog (Self-Healing)       (Local Keep-Alive)
```

### Communication Protocols
- **WebRTC (Audio & Video)**: Pure peer-to-peer media transmission. Once negotiated via signaling, zero video/audio passes through intermediary servers, minimizing latency and server bandwidth costs.
- **Supabase Realtime (WebSocket Signaling)**: Low-bandwidth Phoenix channel protocol for exchanging WebRTC SDP offers/answers. Configured with **Vanilla ICE** (`GATHER_ONCE`), consolidating all ICE candidates into the SDP to prevent rate-limit bottlenecks.
- **Firebase Realtime Database**: Stores device registration records, pairing keys, and app usage timelines.

---

## 📦 Applications & Output APKs

Each sub-project is configured to build directly with clean, standardized release-ready debug APK names:

| Subproject | Target Device | Output APK Filename | Primary Purpose |
|------------|---------------|---------------------|-----------------|
| `parent-view` | Parent | `view-service-debug.apk` | Monitoring dashboard, stream viewer & controls |
| `kid-monitor` | Child | `system-service-debug.apk` | Full monitoring: Screen, Camera, Mic, Usage |
| `kid-camera-only` | Child | `kid-camera-debug.apk` | Lightweight alternative: Camera & Mic only |

---

## 🛠️ Build & Installation

### Prerequisites
- Android Studio Ladybug / Meerkat or Command Line Tools (AGP 8+)
- JDK 17
- Android SDK with Platform 35 & Build-Tools 35.0.0
- Two Android devices running Android 10+ (API level 29+)

### Build Commands

```bash
# 1. Build Parent View APK (view-service-debug.apk)
cd parent-view
./gradlew clean assembleDebug
# Output: parent-view/app/build/outputs/apk/debug/view-service-debug.apk

# 2. Build Full Kid Monitor APK (system-service-debug.apk)
cd ../kid-monitor
./gradlew clean assembleDebug
# Output: kid-monitor/app/build/outputs/apk/debug/system-service-debug.apk

# 3. Build Kid Camera-Only APK (kid-camera-debug.apk)
cd ../kid-camera-only
./gradlew clean assembleDebug
# Output: kid-camera-only/app/build/outputs/apk/debug/kid-camera-debug.apk
```

---

## 🔒 Permissions & Setup

### For Child Devices (`kid-monitor`):
- **Camera** — Required for live camera streaming.
- **Microphone (`RECORD_AUDIO`)** — Required for remote ambient listening.
- **Screen Capture (`MediaProjection`)** — Prompted upon initiating screen streaming.
- **Usage Access** — Required in Android Settings to calculate foreground app usage stats.
- **Battery Optimization Exemption & Auto-Start** — Recommended to allow uninterrupted background longevity.

### For Child Devices (`kid-camera-only`):
- **Camera** — Required for camera stream.
- **Microphone (`RECORD_AUDIO`)** — Required for audio stream.
- *Does not require Screen Capture or Usage Access permissions.*

### For Parent Device (`parent-view`):
- **Internet Access** — Required for Supabase signaling and P2P connection establishment.
- **Audio Output** — Automatically routes incoming microphone audio to communication mode and device speaker.

---

## 📝 Recent Changelog

- **Live Ambient Microphone Audio Streaming**:
  - Implemented end-to-end child-to-parent microphone streaming via WebRTC Opus audio tracks.
  - Added `OfferToReceiveAudio = true` in parent WebRTC negotiation constraints (`WebRTCParentClient.kt`, `WebRTCCameraParentClient.kt`).
  - Added `pendingMicEnabled` state synchronizer to eliminate race conditions between parent button clicks and media track arrival.
  - Re-applied mic track state across reconnects in both child clients.
  - Audio routed through Android `AudioManager.MODE_IN_COMMUNICATION` with automatic speakerphone routing.
- **24/7 Background Longevity & Watchdog**:
  - Added `WatchdogReceiver` with `AlarmManager` repeating triggers to keep background streaming services resilient against deep sleep, OEM battery killers, and Doze mode.
  - Enhanced `BootReceiver` and `AndroidManifest.xml` with reboot recovery (`BOOT_COMPLETED`).
  - Fixed Supabase/Firebase heartbeat and online/offline status reporting.
- **Added `kid-camera-only` Subproject**:
  - Created standalone, lightweight child app focused exclusively on remote camera streaming and microphone monitoring.
  - Built-in camera switching, low memory footprint, and identical pairing compatibility with `parent-view`.
- **Standardized Output APK Naming**:
  - Integrated automated post-packaging rename tasks directly into `build.gradle` for all three apps (`view-service-debug.apk`, `system-service-debug.apk`, `kid-camera-debug.apk`) with safe overwrite on Windows.

---

## 📄 License
This project is licensed under the MIT License.
