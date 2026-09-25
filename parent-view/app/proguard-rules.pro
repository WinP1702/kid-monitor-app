# ── WebRTC ────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── OkHttp ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── MPAndroidChart ────────────────────────────────────────────────────
-keep class com.github.mikephil.** { *; }
-dontwarn com.github.mikephil.**

# ── App classes ───────────────────────────────────────────────────────
-keep class com.parentalcontrol.parentview.** { *; }

# ── Android components ───────────────────────────────────────────────
-keep class * extends android.app.Activity
-keep class * extends android.app.Service

# ── ViewBinding (generated classes) ──────────────────────────────────
-keep class **.databinding.* { *; }
