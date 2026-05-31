package com.vinmusic.innertube

import android.content.Context
import java.security.MessageDigest

/**
 * Stores YouTube Music session cookie for personalized browse/home (Metrolist-style).
 * Paste cookie from browser after logging into music.youtube.com in Settings.
 */
object YTMusicSession {
    private const val PREFS = "vin_music_prefs"
    private const val KEY_COOKIE = "yt_music_cookie"

    fun getCookie(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setCookie(ctx: Context, cookie: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, cookie?.trim().orEmpty())
            .apply()
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
