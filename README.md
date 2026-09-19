# 📱 Kid Monitor App

A parental monitoring Android app suite consisting of two apps:

- **`kid-monitor/`** — Runs on the **child's phone**. Captures screen & app usage, streams live screen and live camera via WebRTC.
- **`parent-view/`** — Runs on the **parent's phone**. Shows live screen, live camera feed, app usage stats, and device management.

## Features

- 🔴 **Live Screen** — Real-time P2P screen stream (WebRTC, 720p@30fps)
- 📷 **Live Camera** — Real-time P2P camera stream from kid's device (WebRTC)
- 🔄 **Camera Switch** — Toggle between front and back camera remotely from parent app
- 📊 **App Usage** — See which apps the child uses and for how long
- 📸 **Screenshot** — Capture a still from the live view
- 📱 **Multi-Device** — Monitor multiple child devices
- 🗑️ **Delete Device** — Remove devices from the parent dashboard
- 🔕 **Stealth Service** — Camera stream runs as a silent background foreground service (no visible notification icon)

## Architecture

```
Kid Phone                    Supabase Realtime (signaling)   Parent Phone
──────────                   ─────────────────────────────   ────────────
Screen capture (WebRTC)  ←── WebRTC SDP/ICE exchange ──────► SurfaceViewRenderer (screen)
Camera stream  (WebRTC)  ←── WebRTC SDP/ICE exchange ──────► SurfaceViewRenderer (camera)
App usage (Firebase RTDB)                                     Device list (Firebase RTDB)
```

- **WebRTC** — Live screen & camera P2P streaming (zero server cost for video data)
- **Supabase Realtime** — WebRTC signaling only (~5KB per session), vanilla ICE (no ICE trickle messages)
- **Firebase RTDB** — Device registration, app usage stats

## Camera Stream Details

- Uses **Vanilla ICE** (GATHER_ONCE) — all ICE candidates are embedded in the SDP offer/answer, avoiding Supabase rate limits
- Kid runs `CameraStreamService` as a **foreground service** (IMPORTANCE_MIN, VISIBILITY_SECRET — no status bar icon, no sound)
- Parent sends a `request` signal and retries every 8 seconds until the kid device connects
- Supports **camera switching** — parent can toggle front/back camera remotely via a `switch` signal
- Kid responds with a `camera-switched` event so the parent UI updates the label accordingly

## Setup

### Prerequisites
- Android Studio
- Two Android phones (API 29+)
- Firebase project (free tier)
- Supabase project (free tier)

### Configuration
1. Add `google-services.json` to both `kid-monitor/app/` and `parent-view/app/`
2. Set your Supabase URL and key in both `app/build.gradle`:
   ```gradle
   buildConfigField "String", "SUPABASE_URL", '"your-url"'
   buildConfigField "String", "SUPABASE_KEY", '"your-key"'
   ```

### Build
```bash
# Kid Monitor
cd kid-monitor
./gradlew assembleDebug

# Parent View
cd parent-view
./gradlew assembleDebug
```

## Requirements
- Android 10+ (API 29)
- Screen capture permission (kid device)
- Camera permission (kid device)
- Usage stats permission (kid device)
- Internet access (both devices)

## License
MIT
