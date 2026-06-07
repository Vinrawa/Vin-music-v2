package com.vinmusic.recommendation.genre

/**
 * Pure query builder for genre-specific InnerTube API searches.
 * 
 * This module is PURE and stateless - no database access, no side effects.
 * All query generation logic is deterministic and testable.
 */
object GenreQueryBuilder {
    
    /**
     * Builds optimized search queries for Rap/Hip-Hop genre
     * 
     * @param subGenre Optional sub-genre: "Trap", "Old School", "Desi Hip-Hop", "UK Drill"
     * @return List of specialized search queries for the sub-genre
     */
    fun buildRapQueries(subGenre: String?): List<String> {
        val baseQueries = when (subGenre?.lowercase()) {
            "trap" -> listOf(
                "trap music hits 2025",
                "trap rap popular songs",
                "trap beats new releases"
            )
            "old school" -> listOf(
                "old school hip hop classics",
                "90s rap hits golden era",
                "classic hip hop anthems"
            )
            "desi hip-hop" -> listOf(
                "desi hip hop 2025",
                "hindi rap songs divine kr\$na",
                "indian hip hop new releases"
            )
            "uk drill" -> listOf(
                "uk drill 2025",
                "uk drill popular hits",
                "uk drill new music"
            )
            else -> listOf(
                "rap hip hop hits 2025",
                "popular hip hop music",
                "rap new releases 2025"
            )
        }
        return baseQueries
    }
    
    /**
     * Builds artist spotlight queries for Rap genre
     * 
     * Includes queries for Kendrick Lamar, Divine, Kr$na, Eminem, and trending artists
     * 
     * @return List of artist-specific search queries
     */
    fun buildRapArtistQueries(): List<String> = listOf(
        "kendrick lamar official music",
        "divine rapper official songs",
        "kr\$na official tracks",
        "eminem official music",
        "trending rap artists 2025",
        "popular hip hop rappers"
    )
    
    /**
     * Builds queries for K-Pop content
     * 
     * @param contentType Optional content type: "dance practice", "live stage", "trending groups", "top soloists"
     * @return List of K-Pop specific search queries
     */
    fun buildKPopQueries(contentType: String?): List<String> {
        return when (contentType?.lowercase()) {
            "dance practice" -> listOf(
                "kpop dance practice official",
                "choreography studio choom",
                "dance practice video"
            )
            "live stage" -> listOf(
                "kpop live performance music show",
                "comeback stage music bank",
                "kpop live stage inkigayo"
            )
            "trending groups" -> listOf(
                "bts official music",
                "blackpink official songs",
                "newjeans official tracks",
                "stray kids official music"
            )
            "top soloists" -> listOf(
                "jungkook official songs",
                "iu official music",
                "taeyeon official tracks"
            )
            else -> listOf(
                "kpop hits 2025",
                "kpop popular songs",
                "kpop new releases"
            )
        }
    }
    
    /**
     * Builds queries for 90s Hits by specific year
     * 
     * @param year Optional year between 1990-1999 (defaults to 1995 if null)
     * @return List of year-specific search queries
     */
    fun build90sQueries(year: Int?): List<String> {
        val targetYear = year ?: 1995
        return listOf(
            "$targetYear hits songs",
            "top songs $targetYear music",
            "popular music $targetYear",
            "best of $targetYear hits"
        )
    }
    
    /**
     * Builds queries for Indie Undiscovered content
     * 
     * Targets unsigned artists and hidden gems with low view counts
     * 
     * @return List of indie undiscovered search queries
     */
    fun buildIndieUndiscoveredQueries(): List<String> = listOf(
        "indie unsigned artist new",
        "underrated indie songs 2025",
        "new indie music hidden gems",
        "indie discoveries underground"
    )
    
    /**
     * Builds queries for Indie Acoustic content
     * 
     * Targets acoustic sessions, unplugged performances, and stripped versions
     * 
     * @return List of indie acoustic search queries
     */
    fun buildIndieAcousticQueries(): List<String> = listOf(
        "indie acoustic session live",
        "unplugged indie performance",
        "acoustic indie official",
        "stripped indie songs"
    )
    
    /**
     * Generic query builder with template substitution
     * 
     * Allows dynamic query generation from templates with variable substitution
     * 
     * @param template Genre query template with placeholders
     * @param vars Map of variable names to values for substitution (e.g., {"artist" -> "BTS"})
     * @return List of queries with variables substituted
     */
    fun buildFromTemplate(template: GenreQueryTemplate, vars: Map<String, String> = emptyMap()): List<String> {
        return template.templates.map { query ->
            var result = query
            vars.forEach { (key, value) ->
                result = result.replace("{$key}", value)
            }
            result
        }
    }
}
