package com.vinmusic.config

import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.vinmusic.BuildConfig

object RemoteConfigHelper {
    private const val TAG = "RemoteConfigHelper"

    // ── Parameter Keys ──────────────────────────────────────────────────────────
    const val KEY_MIN_SUPPORTED_VERSION = "min_supported_version_code"
    const val KEY_LYRICS_PROVIDER_PRIORITY = "lyrics_provider_priority"
    const val KEY_STREAM_EXTRACT_FALLBACK = "stream_extract_fallback_enabled"
    const val KEY_SMART_AUTOPLAY_DEFAULT = "smart_autoplay_default"
    const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    const val KEY_SOUNDCLOUD_CLIENT_ID = "soundcloud_client_id"

    /**
     * Initialize Remote Config with default values and fetch the latest parameters.
     */
    fun init() {
        try {
            val remoteConfig = Firebase.remoteConfig
            
            // Set configurations (fast fetch interval for debug builds, 1 hour for release)
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3600
            }
            remoteConfig.setConfigSettingsAsync(configSettings)

            // Define default values
            val defaults = mapOf(
                KEY_MIN_SUPPORTED_VERSION to 6L,
                KEY_LYRICS_PROVIDER_PRIORITY to "KuGou,NetEase,Jsoup",
                KEY_STREAM_EXTRACT_FALLBACK to true,
                KEY_SMART_AUTOPLAY_DEFAULT to true,
                KEY_LASTFM_API_KEY to "526331c755d4f3a5e8c965230186a29b",
                KEY_SOUNDCLOUD_CLIENT_ID to "ZeISTkUY1Q4so1FsHJKZaJo898iWk0d3"
            )
            remoteConfig.setDefaultsAsync(defaults)

            // Fetch and activate config values
            remoteConfig.fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Remote Config fetched and activated successfully: ${task.result}")
                    } else {
                        Log.w(TAG, "Remote Config fetch failed: ${task.exception?.message}")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Remote Config: ${e.message}", e)
        }
    }

    /**
     * Get Last.fm API Key.
     * Returns empty string if Remote Config is unavailable — callers must handle gracefully.
     */
    fun getLastFmApiKey(): String {
        return try {
            Firebase.remoteConfig.getString(KEY_LASTFM_API_KEY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Last.fm API key from Remote Config", e)
            ""
        }
    }

    /**
     * Get the minimum supported version code.
     */
    fun getMinSupportedVersionCode(): Int {
        return try {
            Firebase.remoteConfig.getLong(KEY_MIN_SUPPORTED_VERSION).toInt()
        } catch (e: Exception) {
            6
        }
    }

    /**
     * Get lyrics provider priority (comma-separated).
     */
    fun getLyricsProviderPriority(): String {
        return try {
            Firebase.remoteConfig.getString(KEY_LYRICS_PROVIDER_PRIORITY)
        } catch (e: Exception) {
            "KuGou,NetEase,Jsoup"
        }
    }

    /**
     * Check if stream extract fallback mode is enabled.
     */
    fun isStreamExtractFallbackEnabled(): Boolean {
        return try {
            Firebase.remoteConfig.getBoolean(KEY_STREAM_EXTRACT_FALLBACK)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Get default smart autoplay state.
     */
    fun getSmartAutoplayDefault(): Boolean {
        return try {
            Firebase.remoteConfig.getBoolean(KEY_SMART_AUTOPLAY_DEFAULT)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Get SoundCloud Client ID from Remote Config with fallback to active default.
     */
    fun getSoundCloudClientId(): String {
        return try {
            val key = Firebase.remoteConfig.getString(KEY_SOUNDCLOUD_CLIENT_ID)
            if (key.isNotBlank()) key else "ZeISTkUY1Q4so1FsHJKZaJo898iWk0d3"
        } catch (e: Exception) {
            "ZeISTkUY1Q4so1FsHJKZaJo898iWk0d3"
        }
    }
}
