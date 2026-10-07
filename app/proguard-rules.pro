# ── Room entities (accessed via reflection by Room compiler) ────────────────────
-keep class com.vinmusic.data.db.** { *; }
-keep class com.vinmusic.recommendation.** { *; }
-keep class com.vinmusic.data.** { *; }

# ── Gson models (deserialized via reflection & TypeTokens) ─────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.vinmusic.lyrics.** { *; }
-keep class com.vinmusic.innertube.** { *; }
-keep class com.vinmusic.recommendation.** { *; }
-keep class com.vinmusic.update.** { *; }
-keep class com.vinmusic.config.** { *; }

# ── Hilt / Dagger (injected via reflection) ────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── OkHttp / Okio (suppress warnings for platform-specific classes) ────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Misc ───────────────────────────────────────────────────────────────────────
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep enum values for Gson
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Strip verbose/debug/info logging from release builds ──────────────────────
# Logs were shipping account emails, visitor tokens and auth-response bodies to
# logcat in production. Keep warn/error for crash diagnosis; drop v/d/i entirely.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Media3 - keep ExoPlayer internals
-keep class androidx.media3.** { *; }
