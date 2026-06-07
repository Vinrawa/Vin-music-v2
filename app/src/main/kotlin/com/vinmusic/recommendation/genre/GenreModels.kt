package com.vinmusic.recommendation.genre

import com.vinmusic.innertube.VideoItem

/**
 * Represents a genre category with its configuration
 */
data class GenreConfig(
    val id: String,
    val displayName: String,
    val subGenres: List<String>,
    val primaryColor: String,
    val secondaryColor: String,
    val visualizerType: VisualizerType
)

enum class VisualizerType {
    VINYL,      // Default for K-Pop, Indie
    BASS,       // Rap/Hip-Hop
    CASSETTE    // 90s Hits
}

/**
 * Represents a scored content item after filtering and reranking
 */
data class ScoredContent(
    val videoItem: VideoItem,
    val score: Double,
    val reasons: List<String>  // e.g., ["Genre Match", "TasteDNA 0.85", "Official"]
)

/**
 * Cache entry with TTL metadata
 */
data class GenreCacheEntry(
    val cacheKey: String,
    val content: List<VideoItem>,
    val timestamp: Long,
    val ttlMillis: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMillis
    fun isFresh(): Boolean = !isExpired()
    fun staleness(): Float = 
        ((System.currentTimeMillis() - timestamp).toFloat() / ttlMillis.toFloat()).coerceIn(0f, 1f)
}

/**
 * User's TasteDNA profile for genre-specific personalization
 */
data class GenreTasteProfile(
    val topGenres: Map<String, Double>,           // Genre -> affinity score
    val topMoods: Map<String, Double>,             // Mood -> affinity score
    val preferredArtists: Set<String>,             // Artist names (normalized)
    val skippedArtists: Set<String>,               // Artists with skip rate > 50%
    val recentlyPlayedArtists: Set<String>,        // Last 7 days
    val excludedVideoIds: Set<String>,             // Already played in timeframe
    val preferredLanguages: Map<String, Double>    // Language -> affinity
)

/**
 * Filter configuration per genre
 */
data class GenreFilterConfig(
    val maxViewCount: Long? = null,               // For "Undiscovered" content
    val minDurationSeconds: Int = 90,
    val maxDurationSeconds: Int = 480,
    val excludeKeywords: List<String> = emptyList(),
    val requireKeywords: List<String> = emptyList(),
    val maxSameArtist: Int = 2,
    val requireOfficial: Boolean = false
)

/**
 * Query template for genre-specific searches
 */
data class GenreQueryTemplate(
    val genre: String,
    val subGenre: String? = null,
    val templates: List<String>,
    val weight: Double = 1.0
)

/**
 * Constants for genre configurations
 */
object GenreConstants {
    val RAP = GenreConfig(
        id = "rap",
        displayName = "Rap/Hip-Hop",
        subGenres = listOf("Trap", "Old School", "Desi Hip-Hop", "UK Drill"),
        primaryColor = "#E63946",  // Red
        secondaryColor = "#1D3557", // Dark Blue
        visualizerType = VisualizerType.BASS
    )
    
    val KPOP = GenreConfig(
        id = "kpop",
        displayName = "K-Pop",
        subGenres = listOf("Trending Groups", "Top Soloists", "Dance Practices", "Live Stages"),
        primaryColor = "#F72585",  // Pink
        secondaryColor = "#7209B7", // Purple
        visualizerType = VisualizerType.VINYL
    )
    
    val NINETIES = GenreConfig(
        id = "90s",
        displayName = "90s Hits",
        subGenres = (1990..1999).map { it.toString() },
        primaryColor = "#008080",  // Teal
        secondaryColor = "#FF8C00", // Orange
        visualizerType = VisualizerType.CASSETTE
    )
    
    val INDIE = GenreConfig(
        id = "indie",
        displayName = "Indie",
        subGenres = listOf("Undiscovered", "Acoustic"),
        primaryColor = "#2A9D8F",  // Turquoise
        secondaryColor = "#E76F51", // Coral
        visualizerType = VisualizerType.VINYL
    )
    
    val ALL_GENRES = listOf(RAP, KPOP, NINETIES, INDIE)
}

/**
 * Cache key generation utilities
 */
object CacheKeys {
    fun genreContent(genre: String, subGenre: String? = null, contentType: String? = null): String {
        val parts = mutableListOf("genre", genre)
        if (subGenre != null) parts.add(subGenre)
        if (contentType != null) parts.add(contentType)
        return parts.joinToString("_")
    }
    
    fun artistSpotlight(genre: String): String = "artist_spotlight_$genre"
    
    fun yearContent(year: Int): String = "year_$year"
    
    fun undiscovered(genre: String): String = "undiscovered_$genre"
    
    fun acoustic(genre: String): String = "acoustic_$genre"
}

/**
 * Cache statistics for monitoring
 */
data class CacheStats(
    val totalEntries: Int,
    val freshCount: Int,
    val staleCount: Int,
    val hitRate: Float
)
