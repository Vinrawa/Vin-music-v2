package com.vinmusic.recommendation.genre

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GenreContentFilter
 * Tests artist diversity and deduplication functions added in Task 4.2
 */
class GenreContentFilterTest {
    
    /**
     * Test artist diversity constraint - verify max songs per artist is respected
     */
    @Test
    fun testApplyArtistDiversity_limitsMaxPerArtist() {
        // Create test data with multiple songs from same artists
        val items = listOf(
            VideoItem("1", "Song 1", "Artist A", "3:00"),
            VideoItem("2", "Song 2", "Artist A", "3:30"),
            VideoItem("3", "Song 3", "Artist A", "4:00"),
            VideoItem("4", "Song 4", "Artist B", "3:20"),
            VideoItem("5", "Song 5", "Artist B", "3:45"),
            VideoItem("6", "Song 6", "Artist C", "3:10")
        )
        
        // Apply artist diversity with max 2 songs per artist
        val result = GenreContentFilter.applyArtistDiversity(items, maxPerArtist = 2)
        
        // Count songs per artist in result
        val artistCounts = result.groupingBy { it.author }.eachCount()
        
        // Verify no artist has more than 2 songs
        artistCounts.values.forEach { count ->
            assertTrue("Artist should have at most 2 songs", count <= 2)
        }
        
        // Verify Artist A has exactly 2 songs (was 3 originally)
        assertEquals(2, artistCounts["Artist A"])
        
        // Verify Artist B has exactly 2 songs (was 2 originally)
        assertEquals(2, artistCounts["Artist B"])
        
        // Verify Artist C has 1 song (was 1 originally)
        assertEquals(1, artistCounts["Artist C"])
        
        // Verify total is 5 songs (2 + 2 + 1)
        assertEquals(5, result.size)
    }
    
    @Test
    fun testApplyArtistDiversity_withMaxOne() {
        // Create test data
        val items = listOf(
            VideoItem("1", "Song 1", "Artist A", "3:00"),
            VideoItem("2", "Song 2", "Artist A", "3:30"),
            VideoItem("3", "Song 3", "Artist B", "3:20"),
            VideoItem("4", "Song 4", "Artist B", "3:45")
        )
        
        // Apply artist diversity with max 1 song per artist
        val result = GenreContentFilter.applyArtistDiversity(items, maxPerArtist = 1)
        
        // Count songs per artist in result
        val artistCounts = result.groupingBy { it.author }.eachCount()
        
        // Verify no artist has more than 1 song
        artistCounts.values.forEach { count ->
            assertEquals("Artist should have exactly 1 song", 1, count)
        }
        
        // Verify total is 2 songs (one from each artist)
        assertEquals(2, result.size)
    }
    
    @Test
    fun testApplyArtistDiversity_withMaxThree() {
        // Create test data with many songs from same artist
        val items = listOf(
            VideoItem("1", "Song 1", "Popular Artist", "3:00"),
            VideoItem("2", "Song 2", "Popular Artist", "3:30"),
            VideoItem("3", "Song 3", "Popular Artist", "4:00"),
            VideoItem("4", "Song 4", "Popular Artist", "3:20"),
            VideoItem("5", "Song 5", "Popular Artist", "3:45")
        )
        
        // Apply artist diversity with max 3 songs per artist
        val result = GenreContentFilter.applyArtistDiversity(items, maxPerArtist = 3)
        
        // Verify exactly 3 songs returned (limited from 5)
        assertEquals(3, result.size)
        
        // Verify all songs are from the same artist
        assertTrue(result.all { it.author == "Popular Artist" })
    }
    
    @Test
    fun testApplyArtistDiversity_caseInsensitive() {
        // Test that artist matching is case-insensitive
        val items = listOf(
            VideoItem("1", "Song 1", "Artist Name", "3:00"),
            VideoItem("2", "Song 2", "ARTIST NAME", "3:30"),
            VideoItem("3", "Song 3", "artist name", "4:00"),
            VideoItem("4", "Song 4", "ArTiSt NaMe", "3:20")
        )
        
        // Apply artist diversity with max 2 songs per artist
        val result = GenreContentFilter.applyArtistDiversity(items, maxPerArtist = 2)
        
        // Verify only 2 songs returned (all variations treated as same artist)
        assertEquals(2, result.size)
    }
    
    /**
     * Test deduplication of similar titles
     */
    @Test
    fun testDeduplicateSimilar_removesDuplicates() {
        // Create test data with similar titles
        val items = listOf(
            VideoItem("1", "Shape of You - Ed Sheeran (Official Video)", "Ed Sheeran", "3:00"),
            VideoItem("2", "Shape of You (Audio)", "Ed Sheeran", "3:30"),
            VideoItem("3", "Blinding Lights - The Weeknd", "The Weeknd", "3:20"),
            VideoItem("4", "Blinding Lights (Official Audio)", "The Weeknd", "3:25"),
            VideoItem("5", "Different Song Entirely", "Another Artist", "4:00")
        )
        
        // Apply deduplication
        val result = GenreContentFilter.deduplicateSimilar(items)
        
        // Verify duplicates are removed
        // Should keep first of each similar pair, plus the unique song
        assertTrue("Should have fewer items after deduplication", result.size < items.size)
        assertTrue("Should have at least 3 unique songs", result.size >= 3)
        
        // Verify the unique song is present
        assertTrue("Different song should be present", 
            result.any { it.title == "Different Song Entirely" })
    }
    
    @Test
    fun testDeduplicateSimilar_keepsFirstOccurrence() {
        // Create test data with exact duplicates
        val items = listOf(
            VideoItem("1", "Song Title", "Artist", "3:00"),
            VideoItem("2", "Song Title", "Artist", "3:00"),
            VideoItem("3", "Song Title", "Artist", "3:00")
        )
        
        // Apply deduplication
        val result = GenreContentFilter.deduplicateSimilar(items)
        
        // Verify only first occurrence is kept
        assertEquals(1, result.size)
        assertEquals("1", result[0].videoId)
    }
    
    @Test
    fun testDeduplicateSimilar_preservesUniqueItems() {
        // Create test data with all unique items
        val items = listOf(
            VideoItem("1", "Completely Different Song A", "Artist A", "3:00"),
            VideoItem("2", "Totally Unique Song B", "Artist B", "3:30"),
            VideoItem("3", "Another Unrelated Track C", "Artist C", "4:00")
        )
        
        // Apply deduplication
        val result = GenreContentFilter.deduplicateSimilar(items)
        
        // Verify all items are preserved (no duplicates)
        assertEquals(items.size, result.size)
    }
    
    @Test
    fun testDeduplicateSimilar_emptyList() {
        // Test with empty list
        val items = emptyList<VideoItem>()
        
        // Apply deduplication
        val result = GenreContentFilter.deduplicateSimilar(items)
        
        // Verify empty result
        assertTrue(result.isEmpty())
    }
    
    /**
     * Test combined artist diversity and deduplication
     */
    @Test
    fun testCombinedArtistDiversityAndDeduplication() {
        // Create test data with both multiple songs per artist and similar titles
        val items = listOf(
            VideoItem("1", "Hit Song - Artist A (Official)", "Artist A", "3:00"),
            VideoItem("2", "Hit Song (Audio)", "Artist A", "3:00"),
            VideoItem("3", "Different Song", "Artist A", "3:30"),
            VideoItem("4", "Another Track", "Artist B", "3:20"),
            VideoItem("5", "Yet Another Song", "Artist B", "3:45")
        )
        
        // First apply deduplication
        val deduped = GenreContentFilter.deduplicateSimilar(items)
        
        // Then apply artist diversity
        val result = GenreContentFilter.applyArtistDiversity(deduped, maxPerArtist = 2)
        
        // Verify Artist A has at most 2 songs after deduplication
        val artistACounts = result.count { it.author == "Artist A" }
        assertTrue("Artist A should have at most 2 songs", artistACounts <= 2)
        
        // Verify Artist B has at most 2 songs
        val artistBCounts = result.count { it.author == "Artist B" }
        assertTrue("Artist B should have at most 2 songs", artistBCounts <= 2)
    }
}
