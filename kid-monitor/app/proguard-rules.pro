# ── WebRTC ────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── OkHttp ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── Supabase signaling (JSON parsing via org.json — built-in, no rules needed) ─

# ── App classes ───────────────────────────────────────────────────────
-keep class com.parentalcontrol.kidmonitor.** { *; }

# ── Android components ───────────────────────────────────────────────
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Activity
-keep class * extends android.app.admin.DeviceAdminReceiver
