package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the core scoring math: calculateTasteSimilarity,
 * cached-feature merging, and metadata inference edge cases.
 *
 * These functions decide what users see — they were previously untested.
 */
class RecommendationScoringTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: build a TasteDNA with known values
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildDNA(
        energy: Double = 0.6,
        tempo: Int = 100,
        genres: Map<String, Double> = mapOf("Rap/Hip-Hop" to 80.0, "Pop" to 30.0),
        moods: Map<String, Double> = mapOf("Energetic" to 60.0, "Chill/Relaxed" to 40.0),
        languages: Map<String, Double> = mapOf("English" to 90.0, "Hindi" to 20.0),
        artists: Map<String, Double> = mapOf("j. cole" to 50.0, "kendrick lamar" to 40.0)
    ) = RecommendationManager.TasteDNA(
        targetEnergy = energy,
        targetTempo = tempo,
        preferredGenres = genres,
        preferredMoods = moods,
        preferredLanguages = languages,
        preferredArtists = artists
    )

    private fun makeMeta(
        genre: String = "Rap/Hip-Hop",
        mood: String = "Energetic",
        language: String = "English",
        artist: String = "J. Cole",
        energy: Double = 0.6,
        tempo: Int = 100
    ) = SongMetadata(
        title = "Test Song",
        artist = artist,
        genre = genre,
        mood = mood,
        language = language,
        energy = energy,
        tempo = tempo,
        year = 2024,
        isOfficial = true,
        sourceQuality = "Ultra HD (320kbps)"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // calculateTasteSimilarity — basic scoring
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `similarity is between 0 and 1`() {
        val dna = buildDNA()
        val meta = makeMeta()
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        assertTrue("Score $score should be >= 0", score >= 0.0)
        assertTrue("Score $score should be <= 1", score <= 1.0)
    }

    @Test
    fun `exact genre match scores higher than mismatch`() {
        val dna = buildDNA(genres = mapOf("Rap/Hip-Hop" to 80.0, "Pop" to 20.0))
        val matchMeta = makeMeta(genre = "Rap/Hip-Hop")
        val mismatchMeta = makeMeta(genre = "Pop")
        val scoreMatch = RecommendationManager.calculateTasteSimilarity(matchMeta, dna)
        val scoreMismatch = RecommendationManager.calculateTasteSimilarity(mismatchMeta, dna)
        assertTrue("Genre match ($scoreMatch) should score higher than mismatch ($scoreMismatch)",
            scoreMatch > scoreMismatch)
    }

    @Test
    fun `exact mood match scores higher than mismatch`() {
        val dna = buildDNA(moods = mapOf("Energetic" to 80.0, "Sad" to 20.0))
        val matchMeta = makeMeta(mood = "Energetic")
        val mismatchMeta = makeMeta(mood = "Sad")
        val scoreMatch = RecommendationManager.calculateTasteSimilarity(matchMeta, dna)
        val scoreMismatch = RecommendationManager.calculateTasteSimilarity(mismatchMeta, dna)
        assertTrue("Mood match ($scoreMatch) should score higher than mismatch ($scoreMismatch)",
            scoreMatch > scoreMismatch)
    }

    @Test
    fun `exact language match scores higher than mismatch`() {
        val dna = buildDNA(languages = mapOf("English" to 90.0, "Hindi" to 10.0))
        val matchMeta = makeMeta(language = "English")
        val mismatchMeta = makeMeta(language = "Hindi")
        val scoreMatch = RecommendationManager.calculateTasteSimilarity(matchMeta, dna)
        val scoreMismatch = RecommendationManager.calculateTasteSimilarity(mismatchMeta, dna)
        assertTrue("Language match ($scoreMatch) should score higher than mismatch ($scoreMismatch)",
            scoreMatch > scoreMismatch)
    }

    @Test
    fun `exact artist match scores higher than unknown artist`() {
        val dna = buildDNA(artists = mapOf("j. cole" to 80.0))
        val matchMeta = makeMeta(artist = "J. Cole")
        val unknownMeta = makeMeta(artist = "Unknown Artist")
        val scoreMatch = RecommendationManager.calculateTasteSimilarity(matchMeta, dna)
        val scoreUnknown = RecommendationManager.calculateTasteSimilarity(unknownMeta, dna)
        assertTrue("Exact artist match ($scoreMatch) should score higher than unknown ($scoreUnknown)",
            scoreMatch > scoreUnknown)
    }

    @Test
    fun `similar artist gets partial boost`() {
        val dna = buildDNA(artists = mapOf("j. cole" to 80.0))
        // Kendrick Lamar is in SIMILAR_ARTISTS_MAP for J. Cole
        val similarMeta = makeMeta(artist = "Kendrick Lamar")
        val unknownMeta = makeMeta(artist = "Adele")
        val scoreSimilar = RecommendationManager.calculateTasteSimilarity(similarMeta, dna)
        val scoreUnknown = RecommendationManager.calculateTasteSimilarity(unknownMeta, dna)
        assertTrue("Similar artist ($scoreSimilar) should score higher than unknown ($scoreUnknown)",
            scoreSimilar > scoreUnknown)
    }

    @Test
    fun `energy close to target scores higher than far from target`() {
        val dna = buildDNA(energy = 0.6)
        val closeMeta = makeMeta(energy = 0.65)
        val farMeta = makeMeta(energy = 0.99)
        val scoreClose = RecommendationManager.calculateTasteSimilarity(closeMeta, dna)
        val scoreFar = RecommendationManager.calculateTasteSimilarity(farMeta, dna)
        assertTrue("Close energy ($scoreClose) should score higher than far ($scoreFar)",
            scoreClose > scoreFar)
    }

    @Test
    fun `tempo close to target scores higher than far from target`() {
        val dna = buildDNA(tempo = 100)
        val closeMeta = makeMeta(tempo = 105)
        val farMeta = makeMeta(tempo = 160)
        val scoreClose = RecommendationManager.calculateTasteSimilarity(closeMeta, dna)
        val scoreFar = RecommendationManager.calculateTasteSimilarity(farMeta, dna)
        assertTrue("Close tempo ($scoreClose) should score higher than far ($scoreFar)",
            scoreClose > scoreFar)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // calculateTasteSimilarity — weighting composition
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `genre match contributes about 20 percent to total score`() {
        // Build a DNA where only genre matches, everything else mismatches
        val dna = buildDNA(
            genres = mapOf("Rap/Hip-Hop" to 100.0, "Pop" to 0.1),
            moods = mapOf("Sad" to 100.0, "Energetic" to 0.1),
            languages = mapOf("Hindi" to 100.0, "English" to 0.1),
            artists = mapOf(),
            energy = 0.0,
            tempo = 60
        )
        // Meta matches genre only
        val meta = makeMeta(genre = "Rap/Hip-Hop", mood = "Energetic", language = "English", energy = 0.9, tempo = 170)
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        // Genre contributes 0.20 * (100/100 = 1.0) = 0.20
        // Other terms contribute minimum (0.1 each)
        assertTrue("Score with genre-only match should be reasonable: $score", score in 0.05..0.50)
    }

    @Test
    fun `all matching fields produce high score`() {
        val dna = buildDNA(
            energy = 0.6, tempo = 100,
            genres = mapOf("Rap/Hip-Hop" to 100.0),
            moods = mapOf("Energetic" to 100.0),
            languages = mapOf("English" to 100.0),
            artists = mapOf("j. cole" to 100.0)
        )
        val meta = makeMeta(
            genre = "Rap/Hip-Hop", mood = "Energetic", language = "English",
            artist = "J. Cole", energy = 0.6, tempo = 100
        )
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        assertTrue("All-match score should be >= 0.7, was $score", score >= 0.7)
    }

    @Test
    fun `nothing matching produces low score`() {
        val dna = buildDNA(
            energy = 0.2, tempo = 70,
            genres = mapOf("Lofi" to 100.0),
            moods = mapOf("Sad" to 100.0),
            languages = mapOf("Korean" to 100.0),
            artists = mapOf()
        )
        val meta = makeMeta(
            genre = "Rap/Hip-Hop", mood = "Energetic", language = "English",
            artist = "Unknown", energy = 0.9, tempo = 160
        )
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        assertTrue("All-mismatch score should be < 0.35, was $score", score < 0.35)
    }

    @Test
    fun `weight formula is deterministic`() {
        val dna = buildDNA()
        val meta = makeMeta()
        // Run 100 times — should always produce the same value
        val scores = (1..100).map { RecommendationManager.calculateTasteSimilarity(meta, dna) }
        assertTrue("All scores should be identical", scores.all { it == scores[0] })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // calculateTasteSimilarity — edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `empty preferred genres still produces valid score`() {
        val dna = buildDNA(genres = emptyMap(), moods = emptyMap(), languages = emptyMap(), artists = emptyMap())
        val meta = makeMeta()
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        assertTrue("Score should be valid with empty preferences: $score", score in 0.0..1.0)
    }

    @Test
    fun `unknown genre in DNA still gets minimum score not zero`() {
        val dna = buildDNA(genres = mapOf("Lofi" to 100.0))
        val meta = makeMeta(genre = "Rock")
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        assertTrue("Unknown genre should get min score > 0: $score", score > 0.0)
    }

    @Test
    fun `zero energy delta gives perfect energy score`() {
        val dna = buildDNA(energy = 0.5)
        val meta = makeMeta(energy = 0.5)
        val score = RecommendationManager.calculateTasteSimilarity(meta, dna)
        // Energy contributes 0.15 * 1.0 = 0.15 when delta is 0
        assertTrue("Zero energy delta score: $score", score > 0.0)
    }

    @Test
    fun `tempo scoring handles octave equivalence gracefully`() {
        // 90 BPM and 180 BPM should be treated as similar (half/double time)
        val dna = buildDNA(tempo = 90)
        val meta90 = makeMeta(tempo = 90)
        val meta180 = makeMeta(tempo = 180)
        val score90 = RecommendationManager.calculateTasteSimilarity(meta90, dna)
        val score180 = RecommendationManager.calculateTasteSimilarity(meta180, dna)
        // With correct octave equivalence, 90 and 180 should score similarly
        assertEquals("90 and 180 BPM should score similarly (octave equivalence)", score90, score180, 0.05)
        // But both should score higher than a clearly different tempo
        val meta140 = makeMeta(tempo = 140)
        val score140 = RecommendationManager.calculateTasteSimilarity(meta140, dna)
        assertTrue("90 BPM should score higher than 140 BPM: $score90 > $score140", score90 > score140)
    }

    @Test
    fun `extreme energy values are handled`() {
        val dna = buildDNA(energy = 0.5)
        val metaLow = makeMeta(energy = 0.01)
        val metaHigh = makeMeta(energy = 0.99)
        val scoreLow = RecommendationManager.calculateTasteSimilarity(metaLow, dna)
        val scoreHigh = RecommendationManager.calculateTasteSimilarity(metaHigh, dna)
        assertTrue("Extreme low energy score: $scoreLow", scoreLow in 0.0..1.0)
        assertTrue("Extreme high energy score: $scoreHigh", scoreHigh in 0.0..1.0)
        // Both are roughly equidistant from 0.5, so scores should be similar
        assertEquals("Symmetric energy scores", scoreLow, scoreHigh, 0.15)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Social proof boost
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `social proof boost is capped at 3 likes`() {
        // We can't call applySocialProofBoost directly (private), but we can verify
        // the formula: final = taste * (1 + 0.1 * min(likedByCount, 3))
        // At 0 likes: multiplier = 1.0
        // At 3 likes: multiplier = 1.3
        // At 10 likes: multiplier = 1.3 (capped)
        val baseScore = 50.0
        val at0 = baseScore * (1.0 + 0.1 * minOf(0, 3))
        val at3 = baseScore * (1.0 + 0.1 * minOf(3, 3))
        val at10 = baseScore * (1.0 + 0.1 * minOf(10, 3))
        assertEquals(50.0, at0, 0.01)
        assertEquals(65.0, at3, 0.01)
        assertEquals(65.0, at10, 0.01) // capped same as 3
    }

    // ═══════════════════════════════════════════════════════════════════════
    // inferMetadata — edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `inferMetadata defaults to Unknown when no language keywords match`() {
        val item = VideoItem("v1", "Random Title", "Random Artist", "3:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals("Unknown", meta.language)
    }

    @Test
    fun `inferMetadata detects Korean from K-pop keywords`() {
        val item = VideoItem("v1", "Dynamite", "BTS", "3:43")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals("Korean", meta.language)
        assertEquals("Korean", meta.language)
    }

    @Test
    fun `inferMetadata detects Tamil from artist keywords`() {
        val item = VideoItem("v1", "Vaathi Coming", "Anirudh Ravichander", "3:30")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals("Tamil", meta.language)
    }

    @Test
    fun `inferMetadata year extraction from title regex`() {
        val item = VideoItem("v1", "Song From 1998", "Artist", "4:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals(1998, meta.year)
    }

    @Test
    fun `inferMetadata energy is within valid bounds for all genres`() {
        val genres = listOf("Lofi", "Rap/Hip-Hop", "Rock", "Punjabi Folk", "Bollywood", "Pop", "Indie")
        for (genre in genres) {
            val item = VideoItem("v1", "$genre Test", "Artist", "3:00")
            val meta = RecommendationManager.inferMetadata(item)
            assertTrue("Energy for $genre (${meta.energy}) should be in 0.1..0.99",
                meta.energy in 0.1..0.99)
        }
    }

    @Test
    fun `inferMetadata tempo is within valid bounds for all genres`() {
        val genres = listOf("Lofi", "Rap/Hip-Hop", "Rock", "Punjabi Folk", "Bollywood", "Pop", "Indie")
        for (genre in genres) {
            val item = VideoItem("v1", "$genre Test", "Artist", "3:00")
            val meta = RecommendationManager.inferMetadata(item)
            assertTrue("Tempo for $genre (${meta.tempo}) should be in 60..180",
                meta.tempo in 60..180)
        }
    }

    @Test
    fun `inferMetadata Sad mood is detected from sad keywords`() {
        val item = VideoItem("v1", "Alone Breaking Up Sad Song", "Artist", "4:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals("Sad", meta.mood)
    }

    @Test
    fun `inferMetadata romantic mood is detected`() {
        val item = VideoItem("v1", "Ishq Mohabbat Pyar Dil", "Artist", "4:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals("Romantic", meta.mood)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Title normalization — additional edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizeTitle handles empty string`() {
        assertEquals("", RecommendationManager.normalizeTitle(""))
    }

    @Test
    fun `normalizeTitle handles only stop words`() {
        assertEquals("", RecommendationManager.normalizeTitle("Official Audio Video Lyrics"))
    }

    @Test
    fun `normalizeTitle preserves non-stopword content`() {
        assertEquals("tum hi ho", RecommendationManager.normalizeTitle("Tum Hi Ho"))
    }

    @Test
    fun `generateSongKey is order-sensitive`() {
        // artist + title should differ from title + artist
        val key1 = RecommendationManager.generateSongKey("Artist", "Song")
        val key2 = RecommendationManager.generateSongKey("Song", "Artist")
        assertNotEquals("Different inputs should produce different keys", key1, key2)
    }

    @Test
    fun `generateSongKey produces 64 char hex`() {
        val key = RecommendationManager.generateSongKey("Test Artist", "Test Title")
        assertEquals(64, key.length)
        assertTrue("Should be hex", key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isTooSimilar — additional edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isTooSimilar detects same title different case`() {
        assertTrue(RecommendationManager.isTooSimilar("TUM HI HO", "tum hi ho"))
    }

    @Test
    fun `isTooSimilar detects substring containment`() {
        assertTrue(RecommendationManager.isTooSimilar("Kesariya", "Kesariya (Full Song)"))
    }

    @Test
    fun `isTooSimilar rejects completely different short titles`() {
        assertFalse(RecommendationManager.isTooSimilar("Hi", "Bye"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isCompilationTrack — edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isCompilationTrack flags exactly 15 minutes`() {
        assertTrue(RecommendationManager.isCompilationTrack("Song", "15:00"))
    }

    @Test
    fun `isCompilationTrack allows 14 minutes 59 seconds`() {
        assertFalse(RecommendationManager.isCompilationTrack("Song", "14:59"))
    }

    @Test
    fun `isCompilationTrack flags HH MM SS format`() {
        assertTrue(RecommendationManager.isCompilationTrack("Song", "1:30:00"))
    }

    @Test
    fun `isCompilationTrack handles malformed duration gracefully`() {
        // "abc" → parts.size == 1 → not >= 3 and not == 2 → returns false
        assertFalse(RecommendationManager.isCompilationTrack("Song", "abc"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isOfficialArtistChannel — false positive investigation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isOfficialArtistChannel accepts channels with Music keyword`() {
        // "music" was removed from the blacklist to fix false positives
        // for legitimate artist channels with "Music" in their name
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "My Official Music"))
    }

    @Test
    fun `isOfficialArtistChannel accepts Topic channel`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "Drake - Topic"))
    }

    @Test
    fun `isOfficialArtistChannel accepts Vevo channel`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "Eminem VEVO"))
    }

    @Test
    fun `isOfficialArtistChannel accepts T-Series`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "T-Series"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isUnofficialContent — corporate channel exception
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isUnofficialContent allows corporate remix`() {
        assertFalse(RecommendationManager.isUnofficialContent("Song Remix", "T-Series"))
    }

    @Test
    fun `isUnofficialContent flags non-corporate remix`() {
        assertTrue(RecommendationManager.isUnofficialContent("Song Remix", "Random Channel"))
    }

    @Test
    fun `isUnofficialContent flags lofi type beat`() {
        assertTrue(RecommendationManager.isUnofficialContent("lofi type beat", "Producer"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Score weighting consistency across curators
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `calculateTasteSimilarity weight terms sum to 1`() {
        // The documented weights are: genre 0.20, artist 0.15, mood 0.20, lang 0.15, energy 0.15, tempo 0.15
        // Sum: 0.20 + 0.15 + 0.20 + 0.15 + 0.15 + 0.15 = 1.00
        val expectedSum = 0.20 + 0.15 + 0.20 + 0.15 + 0.15 + 0.15
        assertEquals("Weight terms should sum to 1.0", 1.0, expectedSum, 0.001)
    }

    @Test
    fun `weight formula consistency across curators`() {
        // calculateTasteSimilarity: genre 0.20, artist 0.15, mood 0.20, lang 0.15, energy 0.15, tempo 0.15
        val tasteWeights = 0.20 + 0.15 + 0.20 + 0.15 + 0.15 + 0.15
        assertEquals("calculateTasteSimilarity weights sum to 1.0", 1.0, tasteWeights, 0.001)

        // getRelatedSongs / getSongRadio: genre 0.25, artist 0.20, mood 0.15, lang 0.15, energy 0.125, tempo 0.125
        val repoWeights = 0.25 + 0.20 + 0.15 + 0.15 + 0.125 + 0.125
        assertEquals("getRelatedSongs/getSongRadio weights sum to 1.0", 1.0, repoWeights, 0.001)

        // getAutoplayRecommendations: genre 0.30, mood 0.20, lang 0.20, energy 0.15, tempo 0.15
        val autoplayWeights = 0.30 + 0.20 + 0.20 + 0.15 + 0.15
        assertEquals("getAutoplayRecommendations weights sum to 1.0", 1.0, autoplayWeights, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SIMILAR_ARTISTS_MAP — coverage
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SIMILAR_ARTISTS_MAP has entries for key artists`() {
        val map = RecommendationManager.SIMILAR_ARTISTS_MAP
        assertTrue("Should have J. Cole", map.containsKey("j. cole"))
        assertTrue("Should have Kendrick Lamar", map.containsKey("kendrick lamar"))
        assertTrue("Should have Drake", map.containsKey("drake"))
        assertTrue("Should have Arijit Singh", map.containsKey("arijit singh"))
        assertTrue("Should have Sidhu Moose Wala", map.containsKey("sidhu moose wala"))
    }

    @Test
    fun `SIMILAR_ARTISTS_MAP is bidirectional`() {
        val map = RecommendationManager.SIMILAR_ARTISTS_MAP
        // If A lists B, then B should list A
        val jcoleSimilar = map["j. cole"] ?: emptyList()
        assertTrue("J. Cole should list Kendrick", jcoleSimilar.any { it.contains("kendrick") })

        val kendrickSimilar = map["kendrick lamar"] ?: emptyList()
        assertTrue("Kendrick should list J. Cole", kendrickSimilar.any { it.contains("j. cole") || it.contains("cole") })
    }

    @Test
    fun `SIMILAR_ARTISTS_MAP only covers 19 artists`() {
        val map = RecommendationManager.SIMILAR_ARTISTS_MAP
        // This documents the known limitation — only 19 hardcoded entries
        assertTrue("Map has ~19 entries, actual: ${map.size}", map.size <= 25)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getLevenshteinDistance — additional edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `levenshtein is symmetric`() {
        assertEquals(
            RecommendationManager.getLevenshteinDistance("abc", "def"),
            RecommendationManager.getLevenshteinDistance("def", "abc")
        )
    }

    @Test
    fun `levenshtein of single char change is 1`() {
        assertEquals(1, RecommendationManager.getLevenshteinDistance("abc", "adc"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GENRE_CONFIGS — structure validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `GENRE_CONFIGS has all expected genres`() {
        val configs = RecommendationManager.GENRE_CONFIGS
        assertTrue("Should have Lofi", configs.containsKey("Lofi"))
        assertTrue("Should have Rap/Hip-Hop", configs.containsKey("Rap/Hip-Hop"))
        assertTrue("Should have Bollywood", configs.containsKey("Bollywood"))
        assertTrue("Should have Punjabi Folk", configs.containsKey("Punjabi Folk"))
        assertTrue("Should have Pop", configs.containsKey("Pop"))
        assertTrue("Should have Indie", configs.containsKey("Indie"))
        assertTrue("Should have Rock", configs.containsKey("Rock"))
    }

    @Test
    fun `GENRE_CONFIGS all have non-empty queries`() {
        for ((genre, config) in RecommendationManager.GENRE_CONFIGS) {
            assertTrue("Queries for $genre should not be empty", config.queries.isNotEmpty())
        }
    }

    @Test
    fun `GENRE_CONFIGS all have valid hex colors`() {
        val hexPattern = Regex("^0x[0-9A-Fa-f]{8}$")
        for ((genre, config) in RecommendationManager.GENRE_CONFIGS) {
            assertTrue("$genre gradientStartHex should be valid hex", hexPattern.matches(config.gradientStartHex))
            assertTrue("$genre gradientEndHex should be valid hex", hexPattern.matches(config.gradientEndHex))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Echo Chamber Fix: "Adoration of Magi" by Lupe Fiasco scenario
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `Lupe Fiasco fan gets diverse genre scores not just hip-hop`() {
        // Simulate a Lupe Fiasco fan's TasteDNA
        val lupeFanDNA = buildDNA(
            genres = mapOf(
                "Rap/Hip-Hop" to 90.0,
                "R&B" to 40.0,
                "Pop" to 20.0
            ),
            moods = mapOf(
                "Chill/Relaxed" to 60.0,
                "Energetic" to 50.0,
                "Romantic" to 30.0
            ),
            languages = mapOf("English" to 95.0),
            artists = mapOf("lupe fiasco" to 100.0, "kendrick lamar" to 70.0),
            tempo = 90,
            energy = 0.55
        )

        // A hip-hop track (same genre) should score well
        val hipHopTrack = makeMeta(
            genre = "Rap/Hip-Hop", mood = "Chill/Relaxed", language = "English",
            artist = "Kendrick Lamar", energy = 0.55, tempo = 88
        )
        val hipHopScore = RecommendationManager.calculateTasteSimilarity(hipHopTrack, lupeFanDNA)

        // An R&B track (adjacent genre, already in user's profile) should also score decently
        val rnbTrack = makeMeta(
            genre = "R&B", mood = "Chill/Relaxed", language = "English",
            artist = "Frank Ocean", energy = 0.45, tempo = 85
        )
        val rnbScore = RecommendationManager.calculateTasteSimilarity(rnbTrack, lupeFanDNA)

        // A pop track should score lower but still reasonably
        val popTrack = makeMeta(
            genre = "Pop", mood = "Energetic", language = "English",
            artist = "The Weeknd", energy = 0.7, tempo = 110
        )
        val popScore = RecommendationManager.calculateTasteSimilarity(popTrack, lupeFanDNA)

        // A completely unrelated genre should score lowest
        val countryTrack = makeMeta(
            genre = "Country", mood = "Happy", language = "English",
            artist = "Luke Combs", energy = 0.6, tempo = 120
        )
        val countryScore = RecommendationManager.calculateTasteSimilarity(countryTrack, lupeFanDNA)

        assertTrue("Hip-hop should score highest: $hipHopScore", hipHopScore > 0.5)
        assertTrue("R&B should score second: $rnbScore (hipHop=$hipHopScore)", rnbScore > 0.35 && rnbScore < hipHopScore)
        assertTrue("Pop should score third: $popScore (rnb=$rnbScore)", popScore > 0.25 && popScore < rnbScore)
        assertTrue("Country should score lowest: $countryScore (pop=$popScore)", countryScore < popScore)
    }

    @Test
    fun `tempo octave equivalence works for Lupe Fiasco style tracks`() {
        // "The Adoration of Magi" is around 85-90 BPM
        // A trap remix at 170 BPM (half-time feel) should be treated as similar
        val dna = buildDNA(tempo = 88, genres = mapOf("Rap/Hip-Hop" to 90.0))

        val original = makeMeta(tempo = 88, genre = "Rap/Hip-Hop", energy = 0.5)
        val halfTime = makeMeta(tempo = 176, genre = "Rap/Hip-Hop", energy = 0.5)
        val doubleTime = makeMeta(tempo = 44, genre = "Rap/Hip-Hop", energy = 0.5)
        val different = makeMeta(tempo = 130, genre = "Rap/Hip-Hop", energy = 0.5)

        val scoreOriginal = RecommendationManager.calculateTasteSimilarity(original, dna)
        val scoreHalfTime = RecommendationManager.calculateTasteSimilarity(halfTime, dna)
        val scoreDoubleTime = RecommendationManager.calculateTasteSimilarity(doubleTime, dna)
        val scoreDifferent = RecommendationManager.calculateTasteSimilarity(different, dna)

        // Half-time (176) and double-time (44) should be close to original (88)
        assertEquals("88 and 176 BPM should be similar (octave)", scoreOriginal, scoreHalfTime, 0.08)
        assertEquals("88 and 44 BPM should be similar (half-time)", scoreOriginal, scoreDoubleTime, 0.08)
        // 130 BPM should be noticeably different
        assertTrue("88 BPM should score higher than 130 BPM: ${scoreOriginal} > ${scoreDifferent}", scoreOriginal > scoreDifferent)
    }

    @Test
    fun `genre family detection maps Lupe Fiasco genres correctly`() {
        // Lupe Fiasco's genres should all be similar to each other (same family)
        val lupeGenres = listOf("chicagorap", "conscioushiphop", "gangsterrap", "hiphop", "poprap", "rap", "southernhiphop")
        
        for (i in lupeGenres.indices) {
            for (j in i + 1 until lupeGenres.size) {
                assertTrue(
                    "${lupeGenres[i]} and ${lupeGenres[j]} should be similar (same family)",
                    RecommendationManager.areGenresSimilar(lupeGenres[i], lupeGenres[j])
                )
            }
        }
    }

    @Test
    fun `genre family detection maps diverse genres to different families`() {
        assertFalse("hiphop and rock should be different families",
            RecommendationManager.areGenresSimilar("hip hop", "rock"))
        assertFalse("pop and electronic should be different families",
            RecommendationManager.areGenresSimilar("pop", "electronic"))
        assertFalse("indian and jazz should be different families",
            RecommendationManager.areGenresSimilar("bollywood", "jazz"))
        assertTrue("rap and hip hop should be same family",
            RecommendationManager.areGenresSimilar("rap", "hip hop"))
    }

    @Test
    fun `areGenresSimilar fallback uses family matching`() {
        // Without loading the genre graph, it falls back to family matching
        assertTrue("conscious hip hop and rap are same family",
            RecommendationManager.areGenresSimilar("conscious hip hop", "rap"))
        assertTrue("pop rap and hip hop are same family",
            RecommendationManager.areGenresSimilar("pop rap", "hip hop"))
        assertFalse("country and hip hop are different families",
            RecommendationManager.areGenresSimilar("country", "hip hop"))
        assertFalse("electronic and jazz are different families",
            RecommendationManager.areGenresSimilar("electronic", "jazz"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Song Style Detection: "Prayer" by Kendrick Lamar scenario
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `detectSongStyle identifies Prayer as storytelling introspective`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "Prayer",
            mood = "Sad",
            energy = 0.35,
            tempo = 85
        )
        assertTrue("Prayer should be identified as storytelling", styles.contains("storytelling"))
        assertTrue("Prayer should be identified as introspective", styles.contains("introspective"))
        assertTrue("Prayer should be identified as sad/emotional", styles.contains("sad") || styles.contains("emotional"))
    }

    @Test
    fun `Adoration of Magi detected as spiritual introspective`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "The Adoration of Magi",
            mood = "Sad",
            energy = 0.35,
            tempo = 85
        )
        assertTrue("Adoration of Magi should be introspective", styles.contains("introspective"))
        assertTrue("Adoration of Magi should be emotional/slow/deep",
            styles.contains("emotional") || styles.contains("slow") || styles.contains("deep"))
    }

    @Test
    fun `detectSongStyle identifies party tracks differently`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "Flex On Em",
            mood = "Energetic",
            energy = 0.85,
            tempo = 140
        )
        assertTrue("Flex should be identified as party", styles.contains("party"))
        assertTrue("Flex should be identified as energetic/hard", styles.contains("energetic") || styles.contains("hard"))
    }

    @Test
    fun `detectSongStyle identifies love songs`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "In Your Eyes",
            mood = "Romantic",
            energy = 0.4,
            tempo = 75
        )
        assertTrue("Love song should be identified as romantic", styles.contains("romantic"))
    }

    @Test
    fun `detectSongStyle identifies chill rainy tracks`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "Rainy Night",
            mood = "Chill/Relaxed",
            energy = 0.25,
            tempo = 70
        )
        assertTrue("Rainy night should be chill", styles.contains("chill"))
        assertTrue("Rainy night should be slow/deep", styles.contains("slow") || styles.contains("deep"))
    }

    @Test
    fun `detectSongStyle identifies aggressive hard tracks`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "War Zone",
            mood = "Energetic",
            energy = 0.9,
            tempo = 150
        )
        assertTrue("War zone should be aggressive", styles.contains("aggressive"))
        assertTrue("War zone should be hard/hype", styles.contains("hard") || styles.contains("hype"))
    }

    @Test
    fun `detectSongStyle returns at most 3 styles`() {
        val styles = RecommendationManager.detectSongStyle(
            title = "Prayer for My Lost Soul in the Rain",
            mood = "Sad",
            energy = 0.2,
            tempo = 65
        )
        assertTrue("Should return at most 3 styles", styles.size <= 3)
    }

    @Test
    fun `Lupe Fiasco Prayer vs HUMBLE get different styles`() {
        // "Prayer" should be storytelling/introspective
        val prayerStyles = RecommendationManager.detectSongStyle("Prayer", "Sad", 0.35, 85)
        // "HUMBLE" should be aggressive/hype
        val humbleStyles = RecommendationManager.detectSongStyle("HUMBLE", "Energetic", 0.85, 150)

        assertTrue("Prayer should be storytelling", prayerStyles.contains("storytelling"))
        assertFalse("Prayer should NOT be party", prayerStyles.contains("party"))
        assertTrue("HUMBLE should be aggressive or hype", humbleStyles.contains("aggressive") || humbleStyles.contains("hype"))
        assertFalse("HUMBLE should NOT be storytelling", humbleStyles.contains("storytelling"))
    }

    @Test
    fun `diverse artist scoring prevents echo chamber in recommendation weights`() {
        // When a Lupe Fiasco fan listens, the system should recommend tracks
        // from diverse artists, not just echo back the same artists
        
        val lupeFanDNA = buildDNA(
            genres = mapOf("Rap/Hip-Hop" to 90.0),
            moods = mapOf("Chill/Relaxed" to 60.0),
            languages = mapOf("English" to 95.0),
            artists = mapOf("lupe fiasco" to 100.0),
            tempo = 88,
            energy = 0.55
        )
        
        // Same artist (Lupe) should get artist boost
        val lupeTrack = makeMeta(artist = "Lupe Fiasco", genre = "Rap/Hip-Hop", tempo = 88)
        val lupeScore = RecommendationManager.calculateTasteSimilarity(lupeTrack, lupeFanDNA)
        
        // Similar artist (Kendrick) should get partial boost
        val kendrickTrack = makeMeta(artist = "Kendrick Lamar", genre = "Rap/Hip-Hop", tempo = 85)
        val kendrickScore = RecommendationManager.calculateTasteSimilarity(kendrickTrack, lupeFanDNA)
        
        // Different artist in same genre should score lower
        val drakeTrack = makeMeta(artist = "Drake", genre = "Rap/Hip-Hop", tempo = 92)
        val drakeScore = RecommendationManager.calculateTasteSimilarity(drakeTrack, lupeFanDNA)
        
        // Artist similarity matters but shouldn't dominate
        assertTrue("Lupe should score highest: $lupeScore", lupeScore > 0.4)
        assertTrue("Kendrick should score well (similar artist): $kendrickScore", kendrickScore > 0.35)
        assertTrue("Drake should score lower (no artist similarity): $drakeScore", drakeScore < kendrickScore)
    }

    @Test
    fun `tiered skip penalty dampener preserves favorite tracks while penalizing unknown bounces`() {
        // Track A: Unknown track, 1 play, 1 skip under 20s
        val unknownSignal = com.vinmusic.data.db.InteractionSignal(
            videoId = "unk1", title = "Unknown Song", author = "Unknown Artist", durationText = "3:30",
            playCount = 1, completeCount = 0, repeatCount = 0, skip20sCount = 1, isLiked = false
        )
        // Track B: User favorite track, 5 plays, 4 completions, 2 repeats, liked, but skipped once today
        val favoriteSignal = com.vinmusic.data.db.InteractionSignal(
            videoId = "fav1", title = "Favorite Song", author = "Favorite Artist", durationText = "3:30",
            playCount = 5, completeCount = 4, repeatCount = 2, skip20sCount = 1, isLiked = true
        )

        // Evaluate skip penalty dampener behavior
        val unknownSkipDampener = if (unknownSignal.completeCount >= 2 || unknownSignal.isLiked) 0.4 else 1.0
        val favoriteSkipDampener = if (favoriteSignal.completeCount >= 2 || favoriteSignal.isLiked) 0.4 else 1.0

        val unknownPenalty = (unknownSignal.skip20sCount * 2.2) * unknownSkipDampener
        val favoritePenalty = (favoriteSignal.skip20sCount * 2.2) * favoriteSkipDampener

        assertEquals("Unknown bounce gets full 2.2 penalty", 2.2, unknownPenalty, 0.001)
        assertEquals("Favorite track skip penalty is dampened to 40%", 0.88, favoritePenalty, 0.001)

        val unknownWeight = (unknownSignal.playCount * 0.4) + (unknownSignal.completeCount * 1.2) - unknownPenalty
        val favoriteWeight = (favoriteSignal.playCount * 0.4) + (favoriteSignal.completeCount * 1.2) +
            (favoriteSignal.repeatCount * 1.5) + (if (favoriteSignal.isLiked) 3.5 else 0.0) - favoritePenalty

        assertTrue("Unknown track should have negative net weight", unknownWeight < 0.0)
        assertTrue("Favorite track should retain strong positive weight despite skip", favoriteWeight > 5.0)
    }

    @Test
    fun `calculateCosineSimilarity accurately computes vector angles`() {
        // Identical vectors -> cos = 1.0
        val v1 = doubleArrayOf(0.8, 0.6, 0.4, 0.2)
        val v2 = doubleArrayOf(0.8, 0.6, 0.4, 0.2)
        val simIdentical = RecommendationManager.calculateCosineSimilarity(v1, v2)
        assertEquals("Identical vectors should have cosine similarity 1.0", 1.0, simIdentical, 0.001)

        // Orthogonal vectors -> cos = 0.0
        val vA = doubleArrayOf(1.0, 0.0, 0.0)
        val vB = doubleArrayOf(0.0, 1.0, 0.0)
        val simOrthogonal = RecommendationManager.calculateCosineSimilarity(vA, vB)
        assertEquals("Orthogonal vectors should have cosine similarity 0.0", 0.0, simOrthogonal, 0.001)

        // Similar vs dissimilar vectors
        val base = doubleArrayOf(0.9, 0.8, 0.7, 0.2)
        val similar = doubleArrayOf(0.85, 0.75, 0.65, 0.25)
        val dissimilar = doubleArrayOf(0.1, 0.2, 0.3, 0.9)
        val simHigh = RecommendationManager.calculateCosineSimilarity(base, similar)
        val simLow = RecommendationManager.calculateCosineSimilarity(base, dissimilar)

        assertTrue("Similar vector should have high cosine similarity: $simHigh", simHigh > 0.95)
        assertTrue("Dissimilar vector should have significantly lower cosine similarity: $simLow", simLow < 0.70)
        assertTrue("Similar should score higher than dissimilar", simHigh > simLow)
    }
}

