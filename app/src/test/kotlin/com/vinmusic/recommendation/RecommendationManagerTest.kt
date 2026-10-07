package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationManagerTest {

    // --- normalizeTitle ---

    @Test
    fun `normalizeTitle removes brackets punctuation and stop words`() {
        assertEquals("tum hi ho", RecommendationManager.normalizeTitle("Tum Hi Ho (Official Video) [HD]"))
    }

    @Test
    fun `normalizeTitle removes lyrics and audio stop words`() {
        assertEquals("kesariya", RecommendationManager.normalizeTitle("Kesariya - Official Audio | Lyrics"))
    }

    @Test
    fun `normalizeTitle collapses whitespace and lowercases`() {
        assertEquals("channa mereya", RecommendationManager.normalizeTitle("  CHANNA   MEREYA  "))
    }

    @Test
    fun `normalizeTitle keeps stop word substrings inside other words`() {
        // "liver" contains no stop word as a whole word
        assertEquals("believer", RecommendationManager.normalizeTitle("Believer"))
    }

    // --- normalizeArtistName ---

    @Test
    fun `normalizeArtistName strips topic suffix`() {
        assertEquals("arijit singh", RecommendationManager.normalizeArtistName("Arijit Singh - Topic"))
    }

    @Test
    fun `normalizeArtistName strips standalone vevo`() {
        assertEquals("eminem", RecommendationManager.normalizeArtistName("Eminem VEVO"))
    }

    @Test
    fun `normalizeArtistName removes special characters`() {
        assertEquals("apdhillon", RecommendationManager.normalizeArtistName("A.P.Dhillon!"))
    }

    // --- isSimilarArtist ---

    @Test
    fun `isSimilarArtist matches identical artists after normalization`() {
        assertTrue(RecommendationManager.isSimilarArtist("Drake", "drake"))
        assertTrue(RecommendationManager.isSimilarArtist("Arijit Singh - Topic", "Arijit Singh"))
    }

    @Test
    fun `isSimilarArtist matches known similar artists in both directions`() {
        assertTrue(RecommendationManager.isSimilarArtist("Drake", "Kendrick Lamar"))
        assertTrue(RecommendationManager.isSimilarArtist("Kendrick Lamar", "Drake"))
    }

    @Test
    fun `isSimilarArtist rejects unrelated artists`() {
        assertFalse(RecommendationManager.isSimilarArtist("Drake", "Adele"))
    }

    // --- getLevenshteinDistance ---

    @Test
    fun `levenshtein distance of identical strings is zero`() {
        assertEquals(0, RecommendationManager.getLevenshteinDistance("abc", "abc"))
    }

    @Test
    fun `levenshtein distance against empty string is the other length`() {
        assertEquals(3, RecommendationManager.getLevenshteinDistance("", "abc"))
        assertEquals(3, RecommendationManager.getLevenshteinDistance("abc", ""))
    }

    @Test
    fun `levenshtein distance kitten to sitting is three`() {
        assertEquals(3, RecommendationManager.getLevenshteinDistance("kitten", "sitting"))
    }

    // --- isTooSimilar ---

    @Test
    fun `isTooSimilar detects same song with different suffixes`() {
        assertTrue(RecommendationManager.isTooSimilar("Tum Hi Ho", "Tum Hi Ho (Lyrics)"))
        assertTrue(RecommendationManager.isTooSimilar("Kesariya", "Kesariya Official Video"))
    }

    @Test
    fun `isTooSimilar rejects clearly different songs`() {
        assertFalse(RecommendationManager.isTooSimilar("Believer", "Channa Mereya"))
    }

    // --- isNonMusicVideo ---

    @Test
    fun `isNonMusicVideo flags reaction and explainer content`() {
        assertTrue(RecommendationManager.isNonMusicVideo("Song Meaning Explained", "Some Channel"))
        assertTrue(RecommendationManager.isNonMusicVideo("My Reaction To New Album", "Random Guy"))
    }

    @Test
    fun `isNonMusicVideo flags blacklisted channel keywords`() {
        assertTrue(RecommendationManager.isNonMusicVideo("New Single", "Tech Burner"))
    }

    @Test
    fun `isNonMusicVideo allows normal songs`() {
        assertFalse(RecommendationManager.isNonMusicVideo("Tum Hi Ho", "Arijit Singh"))
    }

    // --- isCompilationTitle / isCompilationTrack ---

    @Test
    fun `isCompilationTitle flags jukebox and best-of titles`() {
        assertTrue(RecommendationManager.isCompilationTitle("Best of Arijit Singh Jukebox"))
        assertTrue(RecommendationManager.isCompilationTitle("Top 10 Punjabi Songs"))
    }

    @Test
    fun `isCompilationTitle allows single track titles`() {
        assertFalse(RecommendationManager.isCompilationTitle("Kesariya"))
    }

    @Test
    fun `isCompilationTrack flags tracks longer than an hour`() {
        assertTrue(RecommendationManager.isCompilationTrack("Some Song", "1:02:33"))
    }

    @Test
    fun `isCompilationTrack flags tracks of fifteen minutes or more`() {
        assertTrue(RecommendationManager.isCompilationTrack("Some Song", "16:20"))
    }

    @Test
    fun `isCompilationTrack allows normal length tracks`() {
        assertFalse(RecommendationManager.isCompilationTrack("Some Song", "3:45"))
    }

    // --- channel classification ---

    @Test
    fun `isCorporateOrDistributorChannel recognizes major labels`() {
        assertTrue(RecommendationManager.isCorporateOrDistributorChannel("T-Series"))
        assertTrue(RecommendationManager.isCorporateOrDistributorChannel("Zee Music Company"))
    }

    @Test
    fun `isCorporateOrDistributorChannel rejects independent artists`() {
        assertFalse(RecommendationManager.isCorporateOrDistributorChannel("Anuv Jain"))
    }

    @Test
    fun `isUnofficialContent flags slowed reverb uploads`() {
        assertTrue(RecommendationManager.isUnofficialContent("Kesariya (Slowed + Reverb)", "Lofi Bois"))
    }

    @Test
    fun `isUnofficialContent trusts corporate channels even for remix titles`() {
        assertFalse(RecommendationManager.isUnofficialContent("Kesariya Remix", "T-Series"))
    }

    @Test
    fun `isOfficialArtistChannel accepts topic and corporate channels`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "Arijit Singh - Topic"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "T-Series"))
    }

    @Test
    fun `isOfficialArtistChannel rejects known unofficial uploaders`() {
        assertFalse(RecommendationManager.isOfficialArtistChannel("Song", "SongWeed"))
    }

    // --- inferMetadata ---

    @Test
    fun `inferMetadata detects hindi romantic bollywood track`() {
        val item = VideoItem("v1", "Tum Hi Ho", "Arijit Singh", "4:22")
        val meta = RecommendationManager.inferMetadata(item)

        assertEquals("Hindi", meta.language)
        assertEquals("Bollywood", meta.genre)
        assertEquals("Romantic", meta.mood)
        assertTrue(meta.isOfficial)
    }

    @Test
    fun `inferMetadata detects punjabi folk track`() {
        val item = VideoItem("v2", "Jatt Da Muqabala", "Sidhu Moose Wala", "3:30")
        val meta = RecommendationManager.inferMetadata(item)

        assertEquals("Punjabi", meta.language)
        assertEquals("Punjabi Folk", meta.genre)
    }

    @Test
    fun `inferMetadata keeps energy and tempo within valid bounds`() {
        val items = listOf(
            VideoItem("a1", "Lofi Chill Beats", "Chill Vibes", "3:00"),
            VideoItem("b2", "Gym Workout Rap", "MC Stan", "2:50"),
            VideoItem("c3", "Tum Hi Ho", "Arijit Singh", "4:22")
        )
        for (item in items) {
            val meta = RecommendationManager.inferMetadata(item)
            assertTrue("energy in range for ${item.title}", meta.energy in 0.1..0.99)
            assertTrue("tempo in range for ${item.title}", meta.tempo in 60..180)
        }
    }

    @Test
    fun `inferMetadata extracts year from title when present`() {
        val item = VideoItem("v3", "Old Classic Song 1998", "Some Artist", "4:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals(1998, meta.year)
    }

    // --- generateSongKey ---

    @Test
    fun `generateSongKey returns consistent SHA-256 hex string`() {
        val key1 = RecommendationManager.generateSongKey("Arijit Singh - Topic", "Tum Hi Ho (Official Video) [HD]")
        val key2 = RecommendationManager.generateSongKey("arijit singh", "tum hi ho")
        assertEquals(key1, key2)
        // Check if it is a valid hex SHA-256 (64 hex characters)
        assertTrue(key1.matches(Regex("^[a-f0-9]{64}$")))
    }

    // --- buildQueriesForSeed ---

    @Test
    fun `buildQueriesForSeed produces distinct personalized queries for different seeds`() {
        val seed1 = SongMetadata(
            title = "Autobots",
            artist = "Lupe Fiasco",
            genre = "Rap/Hip-Hop",
            mood = "Energetic",
            language = "English",
            energy = 0.8,
            tempo = 135,
            year = 2024,
            isOfficial = true,
            sourceQuality = "High"
        )
        
        val seed2 = SongMetadata(
            title = "Kesariya",
            artist = "Arijit Singh",
            genre = "Bollywood",
            mood = "Romantic",
            language = "Hindi",
            energy = 0.5,
            tempo = 90,
            year = 2022,
            isOfficial = true,
            sourceQuality = "High"
        )
        
        val seed3 = SongMetadata(
            title = "Lofi Chill Sleep",
            artist = "Sleepy Head",
            genre = "Lofi",
            mood = "Chill/Relaxed",
            language = "English",
            energy = 0.2,
            tempo = 70,
            year = 2025,
            isOfficial = false,
            sourceQuality = "Medium"
        )
        
        val seed4 = SongMetadata(
            title = "Jatt Da Muqabala",
            artist = "Sidhu Moose Wala",
            genre = "Punjabi Folk",
            mood = "Energetic",
            language = "Punjabi",
            energy = 0.85,
            tempo = 140,
            year = 2018,
            isOfficial = true,
            sourceQuality = "High"
        )
        
        val queries1 = RecommendationManager.buildQueriesForSeed(seed1, listOf("Kendrick Lamar"))
        val queries2 = RecommendationManager.buildQueriesForSeed(seed2, listOf("Atif Aslam"))
        val queries3 = RecommendationManager.buildQueriesForSeed(seed3, emptyList())
        val queries4 = RecommendationManager.buildQueriesForSeed(seed4, emptyList())
        
        // Assertions for seed1
        assertTrue(queries1.any { it.contains("Rap Hip Hop") && it.contains("workout energetic") })
        assertTrue(queries1.any { it.contains("artists like Kendrick Lamar") })
        assertTrue(queries1.any { it.contains("similar to Lupe Fiasco") })
        
        // Assertions for seed2
        assertTrue(queries2.any { it.contains("Bollywood") && it.contains("romantic love") && it.contains("hindi") })
        assertTrue(queries2.any { it.contains("artists like Atif Aslam") })
        assertTrue(queries2.any { it.contains("similar to Arijit Singh") })
        
        // Assertions for seed3
        assertTrue(queries3.any { it.contains("Lofi") && it.contains("chill") })
        assertTrue(queries3.any { it.contains("Sleepy Head similar music") })
        
        // Assertions for seed4 (Punjabi Folk normalized to Punjabi)
        assertTrue(queries4.any { it.contains("Punjabi") && !it.contains("Punjabi Folk") && it.contains("workout energetic") && it.contains("punjabi") })
        assertTrue(queries4.any { it.contains("Sidhu Moose Wala similar music") })
        
        // Assert distinctness
        assertFalse(queries1 == queries2)
        assertFalse(queries1 == queries3)
        assertFalse(queries2 == queries3)
        assertFalse(queries1 == queries4)
        assertFalse(queries2 == queries4)
        assertFalse(queries3 == queries4)
    }

    @Test
    fun `isUnofficialContent flags lofi and type beats`() {
        assertTrue(RecommendationManager.isUnofficialContent("lofi sleep beats", "Someone"))
        assertTrue(RecommendationManager.isUnofficialContent("drake type beat energetic", "Producer X"))
        assertTrue(RecommendationManager.isUnofficialContent("hindi lofi mix 2026", "Aesthetic Beats"))
    }

    @Test
    fun `isOfficialArtistChannel rejects uploader channels`() {
        assertFalse(RecommendationManager.isOfficialArtistChannel("Song", "chill nation"))
        assertFalse(RecommendationManager.isOfficialArtistChannel("Song", "lofi girl"))
        assertFalse(RecommendationManager.isOfficialArtistChannel("Song", "bollywood edits"))
    }

    @Test
    fun `inferMetadata recognizes expanded vocabulary for Hindi and Punjabi`() {
        val itemHindi = VideoItem("h1", "Dil De Diya", "Anuv Kuhad", "3:45")
        val metaHindi = RecommendationManager.inferMetadata(itemHindi)
        assertEquals("Hindi", metaHindi.language)

        val itemPunjabi = VideoItem("p1", "Jatt Life", "Karan Sandhu", "3:10")
        val metaPunjabi = RecommendationManager.inferMetadata(itemPunjabi)
        assertEquals("Punjabi", metaPunjabi.language)
    }

    @Test
    fun `isNonMusicVideo does not reject titles containing girl or history`() {
        assertFalse(RecommendationManager.isNonMusicVideo("Brown Rang", "Yo Yo Honey Singh"))
        assertFalse(RecommendationManager.isNonMusicVideo("Barbie Girl", "Aqua"))
        assertFalse(RecommendationManager.isNonMusicVideo("History", "One Direction"))
        assertFalse(RecommendationManager.isNonMusicVideo("Love Story", "Taylor Swift"))
        assertFalse(RecommendationManager.isNonMusicVideo("Good News", "Mac Miller"))
    }

    @Test
    fun `isUnofficialContent allows legitimate artists and titles`() {
        assertFalse(RecommendationManager.isUnofficialContent("Life Goes On", "Oliver Tree"))
        assertFalse(RecommendationManager.isUnofficialContent("Alive", "Sia"))
        assertFalse(RecommendationManager.isUnofficialContent("Dynamite", "BTS"))
        assertFalse(RecommendationManager.isUnofficialContent("Live Forever", "Oasis"))
        assertFalse(RecommendationManager.isUnofficialContent("Undercover", "Two Door Cinema Club"))
    }

    @Test
    fun `isOfficialArtistChannel allows artists with radio, beat, prod, sub in name`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Creep", "Radiohead"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Hey Jude", "The Beatles"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Firestarter", "The Prodigy"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Cradles", "Sub Urban"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Blinding Lights", "The Weeknd"))
    }

    @Test
    fun `isTooSimilar does not falsely match on short common words`() {
        assertFalse(RecommendationManager.isTooSimilar("No", "No Love"))
        assertFalse(RecommendationManager.isTooSimilar("Love", "Love Story"))
        assertFalse(RecommendationManager.isTooSimilar("You", "Without You"))
        assertFalse(RecommendationManager.isTooSimilar("Stay", "Stay With Me"))
    }

    @Test
    fun `isCompatibleQueueLanguage enforces strict language with unknown tolerance`() {
        assertTrue(RecommendationManager.isCompatibleQueueLanguage("Punjabi", "Punjabi", allowUnknown = true))
        assertTrue(RecommendationManager.isCompatibleQueueLanguage("Unknown", "Punjabi", allowUnknown = true))
        assertFalse(RecommendationManager.isCompatibleQueueLanguage("Hindi", "Punjabi", allowUnknown = true))
        assertFalse(RecommendationManager.isCompatibleQueueLanguage("English", "Punjabi", allowUnknown = true))

        assertTrue(RecommendationManager.isCompatibleQueueLanguage("English", "English", allowUnknown = true))
        assertTrue(RecommendationManager.isCompatibleQueueLanguage("Unknown", "English", allowUnknown = true))
        assertFalse(RecommendationManager.isCompatibleQueueLanguage("Punjabi", "English", allowUnknown = true))
    }
}
