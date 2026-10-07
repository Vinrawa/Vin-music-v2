package com.vinmusic.innertube

/**
 * YouTube Music's album and playlist cards expose a single, human-readable
 * subtitle. It can contain a year, type, artist, and/or track count. The old
 * UI treated that whole subtitle as a number, which produced labels such as
 * "2025 tracks". Keep the raw subtitle available, but only call something a
 * track count when the source explicitly says songs, tracks, or videos.
 */
private val trackCountPattern = Regex(
    pattern = """(?i)\b([0-9][0-9,]*(?:\.[0-9]+)?[KMB]?)\s*(?:songs?|tracks?|videos?)\b"""
)

fun AlbumItem.trackCountText(): String? =
    trackCountPattern.find(songCount)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun AlbumItem.trackCountLabel(): String? = trackCountText()?.let { count ->
    val isSingleTrack = count.replace(",", "") == "1"
    "$count ${if (isSingleTrack) "track" else "tracks"}"
}

fun AlbumItem.isSingleRelease(): Boolean =
    playlistId.startsWith("single_") ||
        songCount.contains(Regex("""(?i)\bsingle\b""")) ||
        trackCountText() == "1"

/** A cleaned source subtitle for places where a count is not the only metadata. */
fun AlbumItem.metadataSummary(): String =
    songCount
        .replace(Regex("""(?i)\bsongs?\b"""), "tracks")
        .replace(Regex("""\s*•\s*"""), " • ")
        .trim()
