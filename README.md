# 📱 Kid Monitor App

A parental monitoring Android app suite consisting of two apps:

- **`kid-monitor/`** — Runs on the **child's phone**. Captures screen & app usage, streams live screen via WebRTC.
- **`parent-view/`** — Runs on the **parent's phone**. Shows live screen, app usage stats, and device management.

## Features

- 🔴 **Live Screen** — Real-time P2P video stream (WebRTC, 720p@30fps)
- 📊 **App Usage** — See which apps the child uses and for how long
- 📸 **Screenshot** — Capture a still from the live view
- 📱 **Multi-Device** — Monitor multiple child devices
- 🗑️ **Delete Device** — Remove devices from the parent dashboard

## Architecture

```
Kid Phone                    Supabase Realtime (signaling)   Parent Phone
──────────                   ─────────────────────────────   ────────────
Screen capture (WebRTC)  ←── WebRTC SDP/ICE exchange ──────► SurfaceViewRenderer
App usage (Firebase RTDB)                                     Device list (Firebase RTDB)
```

- **WebRTC** — Live screen P2P streaming (zero server cost for video data)
- **Supabase Realtime** — WebRTC signaling only (~5KB per session)
- **Firebase RTDB** — Device registration, app usage stats

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
- Usage stats permission (kid device)
- Internet access (both devices)

## License
MIT
