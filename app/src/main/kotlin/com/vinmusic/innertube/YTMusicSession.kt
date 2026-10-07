package com.vinmusic.innertube

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Stores YouTube Music session cookie for personalized browse/home (Metrolist-style).
 * Paste cookie from browser after logging into music.youtube.com in Settings.
 *
 * The cookie is a full authenticated Google session, so it lives in
 * EncryptedSharedPreferences (hardware-backed key) rather than plain prefs, and is
 * excluded from backups via res/xml/backup_rules + data_extraction_rules.
 */
object YTMusicSession {
    private const val TAG = "YTMusicSession"
    private const val SECURE_PREFS = "vin_music_secure_prefs"
    private const val KEY_COOKIE = "yt_music_cookie"

    /** Pre-encryption location; read once for migration, then cleared. */
    private const val LEGACY_PREFS = "vin_music_prefs"
    private const val LEGACY_KEY_COOKIE = "yt_music_cookie"

    @Volatile
    private var cachedSecure: SharedPreferences? = null

    private fun securePrefs(ctx: Context): SharedPreferences? {
        cachedSecure?.let { return it }
        return synchronized(this) {
            cachedSecure?.let { return it }
            try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    ctx,
                    SECURE_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                cachedSecure = prefs
                prefs
            } catch (e: Exception) {
                // Corrupted keystore state on some devices — reset and retry once.
                Log.e(TAG, "Creating secure prefs failed, resetting: ${e.message}")
                try {
                    ctx.deleteSharedPreferences(SECURE_PREFS)
                    val masterKey = MasterKey.Builder(ctx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    val prefs = EncryptedSharedPreferences.create(
                        ctx,
                        SECURE_PREFS,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    cachedSecure = prefs
                    prefs
                } catch (e2: Exception) {
                    Log.e(TAG, "Secure prefs unavailable — session will not persist securely", e2)
                    null
                }
            }
        }
    }

    fun getCookie(ctx: Context): String? {
        val secure = securePrefs(ctx)
        if (secure != null) {
            secure.getString(KEY_COOKIE, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            // One-time migration from the legacy plaintext location.
            val legacy = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY_COOKIE, null)?.trim()
            if (!legacy.isNullOrEmpty()) {
                secure.edit().putString(KEY_COOKIE, legacy).apply()
                ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(LEGACY_KEY_COOKIE).apply()
                return legacy
            }
        } else {
            // Secure storage unavailable — fall back to the old location so the
            // feature still works rather than silently losing the session.
            return ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY_COOKIE, null)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun setCookie(ctx: Context, cookie: String?) {
        val value = cookie?.trim().orEmpty()
        securePrefs(ctx)?.edit()?.putString(KEY_COOKIE, if (value.isEmpty()) null else value)?.apply()
        // Never leave a plaintext copy behind.
        ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().remove(LEGACY_KEY_COOKIE).apply()
        YTMusicApi.invalidateSession()
    }

    fun hasCookie(ctx: Context): Boolean = getCookie(ctx) != null

    /** SAPISIDHASH authorization header for music.youtube.com authenticated requests. */
    fun authorizationHeader(cookie: String): String? {
        val sapisid = Regex("""__Secure-3PAPISID=([^;]+)""").find(cookie)?.groupValues?.get(1)?.trim()
            ?: Regex("""__Secure-1PAPISID=([^;]+)""").find(cookie)?.groupValues?.get(1)?.trim()
            ?: Regex("""SAPISID=([^;]+)""").find(cookie)?.groupValues?.get(1)?.trim()
            ?: return null
        val origin = "https://music.youtube.com"
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $origin".toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { b -> "%02x".format(b) }
        return "SAPISIDHASH ${ts}_$hash"
    }
}
