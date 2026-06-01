package com.vinmusic.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsHelper {
    private var firebaseAnalytics: FirebaseAnalytics? = null

    private fun getAnalytics(context: Context): FirebaseAnalytics {
        return firebaseAnalytics ?: synchronized(this) {
            firebaseAnalytics ?: FirebaseAnalytics.getInstance(context.applicationContext).also {
                firebaseAnalytics = it
            }
        }
    }

    /**
     * Log a generic custom event with parameters safely.
     */
    fun logEvent(context: Context, eventName: String, params: Bundle? = null) {
        try {
            getAnalytics(context).logEvent(eventName, params)
        } catch (e: Exception) {
            // Prevent crashes in case Firebase is not fully initialized or config is missing
        }
    }

    /**
     * Log user sign in success event.
     */
    fun logSignInSuccess(context: Context, method: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.METHOD, method)
        }
        logEvent(context, FirebaseAnalytics.Event.LOGIN, bundle)
    }

    /**
     * Log user sign in failure event.
     */
    fun logSignInFailed(context: Context, method: String, error: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.METHOD, method)
            putString("error_message", error.take(100))
        }
        logEvent(context, "sign_in_failed", bundle)
    }

    /**
     * Log user sign out event.
     */
    fun logSignOut(context: Context) {
        logEvent(context, "sign_out")
    }

    /**
     * Log cloud backup initiation.
     */
    fun logCloudBackupInitiated(context: Context) {
        logEvent(context, "cloud_backup_initiated")
    }

    /**
     * Log cloud backup success.
     */
    fun logCloudBackupSuccess(context: Context) {
        logEvent(context, "cloud_backup_success")
    }

    /**
     * Log cloud backup failure.
     */
    fun logCloudBackupFailed(context: Context, error: String) {
        val bundle = Bundle().apply {
            putString("error_message", error.take(100))
        }
        logEvent(context, "cloud_backup_failed", bundle)
    }

    /**
     * Log cloud restore initiation.
     */
    fun logCloudRestoreInitiated(context: Context) {
        logEvent(context, "cloud_restore_initiated")
    }

    /**
     * Log cloud restore success.
     */
    fun logCloudRestoreSuccess(context: Context) {
        logEvent(context, "cloud_restore_success")
    }

    /**
     * Log cloud restore failure.
     */
    fun logCloudRestoreFailed(context: Context, error: String) {
        val bundle = Bundle().apply {
            putString("error_message", error.take(100))
        }
        logEvent(context, "cloud_restore_failed", bundle)
    }

    /**
     * Log song playback start with metadata.
     */
    fun logSongPlaybackStarted(context: Context, videoId: String, title: String, artist: String, isOffline: Boolean) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, videoId)
            putString(FirebaseAnalytics.Param.ITEM_NAME, title)
            putString(FirebaseAnalytics.Param.ITEM_CATEGORY, if (isOffline) "offline" else "online")
            putString("artist", artist)
        }
        logEvent(context, FirebaseAnalytics.Event.SELECT_ITEM, bundle)
    }
}
