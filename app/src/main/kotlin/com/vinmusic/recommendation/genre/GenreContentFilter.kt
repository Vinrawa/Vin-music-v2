package com.vinmusic.recommendation.genre

import com.vinmusic.innertube.VideoItem
import com.vinmusic.recommendation.RecommendationManager

/**
 * Pure content filter module for genre-specific quality filtering.
 * 
 * This module is PURE and stateless - uses RecommendationManager helper functions
 * for compilation/non-music detection but maintains no internal state.
 */
object GenreContentFilter {
    
    /**
     * Master filter pipeline that applies all filters in sequence
     * 
     * @param candidates List of VideoItems to filter
     * @param config Filter configuration (duration, keywords, artist limits)
     * @return Filtered list of VideoItems
     */
    fun filterContent(candidates: List<VideoItem>, config: GenreFilterConfig): List<VideoItem> {
        return candidates
            .filter { !isCompilation(it) }
            .filter { !isNonMusic(it) }
            .filter { isValidDuration(it, config.minDurationSeconds, config.maxDurationSeconds) }
            .filter { !hasExcludedKeywords(it, config.excludeKeywords) }
            .filter { hasRequiredKeywords(it, config.requireKeywords) }
            .filter { !isExcessiveViewCount(it, config.maxViewCount) }
            .let { if (config.requireOfficial) it.filter { item -> isOfficial(item) } else it }
    }
    
    /**
     * Check if item is a compilation/playlist video
     * 
     * Uses RecommendationManager.isCompilationTrack for detection
     */
    fun isCompilation(item: VideoItem): Boolean {
        return RecommendationManager.isCompilationTrack(item.title, item.durationText)
    }
    
    /**
     * Check if item is non-music content (reaction, explanation, etc)
     * 
     * Uses RecommendationManager.isNonMusicVideo for detection
     */
    fun isNonMusic(item: VideoItem): Boolean {
        return RecommendationManager.isNonMusicVideo(item.title, item.author)
    }
    
    /**
     * Check if item has valid duration within specified bounds
     * 
     * Supports both HH:MM:SS and MM:SS formats
     * 
     * @param item VideoItem to check
     * @param minSeconds Minimum duration in seconds
     * @param maxSeconds Maximum duration in seconds
     * @return true if duration is within bounds
     */
    fun isValidDuration(item: VideoItem, minSeconds: Int, maxSeconds: Int): Boolean {
        val parts = item.durationText.split(":")
        val totalSeconds = when (parts.size) {
            3 -> {
                // HH:MM:SS format
                val hours = parts[0].toIntOrNull() ?: return false
                val mins = parts[1].toIntOrNull() ?: return false
                val secs = parts[2].toIntOrNull() ?: return false
                hours * 3600 + mins * 60 + secs
            }
            2 -> {
                // MM:SS format
                val mins = parts[0].toIntOrNull() ?: return false
                val secs = parts[1].toIntOrNull() ?: return false
                mins * 60 + secs
            }
            else -> return false
        }
        
        return totalSeconds in minSeconds..maxSeconds
    }
    
    /**
     * Check if item contains any excluded keywords
     * 
     * @param item VideoItem to check
     * @param keywords List of excluded keywords
     * @return true if any excluded keyword is found
     */
    fun hasExcludedKeywords(item: VideoItem, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        
        val titleLower = item.title.lowercase()
        val authorLower = item.author.lowercase()
        val combined = "$titleLower $authorLower"
        
        return keywords.any { keyword -> 
            combined.contains(keyword.lowercase())
        }
    }
    
    /**
     * Check if item contains all required keywords
     * 
     * @param item VideoItem to check
     * @param keywords List of required keywords (empty list = no requirement)
     * @return true if all required keywords are found or list is empty
     */
    fun hasRequiredKeywords(item: VideoItem, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        
        val titleLower = item.title.lowercase()
        val authorLower = item.author.lowercase()
        val combined = "$titleLower $authorLower"
        
        return keywords.all { keyword -> 
            combined.contains(keyword.lowercase())
        }
    }
    
    /**
     * Check if item exceeds max view count (for undiscovered content)
     * 
     * @param item VideoItem to check
     * @param maxViewCount Maximum view count (null = no limit)
     * @return true if view count exceeds limit
     */
    fun isExcessiveViewCount(item: VideoItem, maxViewCount: Long?): Boolean {
        if (maxViewCount == null) return false
        
        // VideoItem doesn't have view count - this is a placeholder
        // In practice, you'd need to fetch this from API or add to VideoItem model
        // For now, return false to allow all content through
        return false
    }
    
    /**
     * Check if item is from official artist channel
     * 
     * Uses RecommendationManager.isOfficialArtistChannel for detection
     */
    fun isOfficial(item: VideoItem): Boolean {
        return RecommendationManager.isOfficialArtistChannel(item.title, item.author)
    }
    
    /**
     * Apply artist diversity constraint - limit songs per artist
     * 
     * @param items List of VideoItems
     * @param maxPerArtist Maximum songs per artist (1-3 typical)
     * @return Filtered list with artist diversity applied
     */
    fun applyArtistDiversity(items: List<VideoItem>, maxPerArtist: Int): List<VideoItem> {
        val artistCount = mutableMapOf<String, Int>()
        val result = mutableListOf<VideoItem>()
        
        for (item in items) {
            val artistNormalized = RecommendationManager.normalizeArtistName(item.author)
            val count = artistCount.getOrDefault(artistNormalized, 0)
            
            if (count < maxPerArtist) {
                result.add(item)
                artistCount[artistNormalized] = count + 1
            }
        }
        
        return result
    }
    
    /**
     * Deduplicate similar tracks using title similarity
     * 
     * Uses RecommendationManager.isTooSimilar for detection
     * 
     * @param items List of VideoItems
     * @return Deduplicated list
     */
    fun deduplicateSimilar(items: List<VideoItem>): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        
        for (item in items) {
            val normalized = RecommendationManager.normalizeTitle(item.title)
            
            // Check if too similar to any already added item
            var isDuplicate = false
            for (seenTitle in seen) {
                if (RecommendationManager.isTooSimilar(normalized, seenTitle)) {
                    isDuplicate = true
                    break
                }
            }
            
            if (!isDuplicate) {
                result.add(item)
                seen.add(normalized)
            }
        }
        
        return result
    }
    
    /**
     * Preset filter configurations for different genres
     */
    object Presets {
        val RAP = GenreFilterConfig(
            maxViewCount = null,
            minDurationSeconds = 120,  // 2 minutes
            maxDurationSeconds = 420,  // 7 minutes
            excludeKeywords = listOf("reaction", "explained", "interview", "documentary"),
            requireKeywords = emptyList(),
            maxSameArtist = 2,
            requireOfficial = false
        )
        
        val KPOP = GenreFilterConfig(
            maxViewCount = null,
            minDurationSeconds = 90,   // 1.5 minutes
            maxDurationSeconds = 600,  // 10 minutes (for dance practices)
            excludeKeywords = listOf("reaction", "explained", "tutorial", "cover"),
            requireKeywords = emptyList(),
            maxSameArtist = 3,
            requireOfficial = false
        )
        
        val NINETIES = GenreFilterConfig(
            maxViewCount = null,
            minDurationSeconds = 120,  // 2 minutes
            maxDurationSeconds = 360,  // 6 minutes
            excludeKeywords = listOf("remix", "slowed", "reverb", "sped up"),
            requireKeywords = emptyList(),
            maxSameArtist = 2,
            requireOfficial = false
        )
        
        val INDIE_UNDISCOVERED = GenreFilterConfig(
            maxViewCount = 1_000_000L,  // <1M views for undiscovered
            minDurationSeconds = 90,
            maxDurationSeconds = 420,
            excludeKeywords = listOf("cover", "remix", "reaction"),
            requireKeywords = emptyList(),
            maxSameArtist = 1,  // Stricter for undiscovered
            requireOfficial = false
        )
        
        val INDIE_ACOUSTIC = GenreFilterConfig(
            maxViewCount = null,
            minDurationSeconds = 120,
            maxDurationSeconds = 480,
            excludeKeywords = listOf("remix", "edm", "bass boosted"),
            requireKeywords = listOf("acoustic", "unplugged", "live", "session"),
            maxSameArtist = 2,
            requireOfficial = false
        )
    }
}
