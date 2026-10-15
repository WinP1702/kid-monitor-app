# ── WebRTC ────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── OkHttp ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── App classes ───────────────────────────────────────────────────────
-keep class com.parentalcontrol.kidcamera.** { *; }

# ── Android components ───────────────────────────────────────────────
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Activity
