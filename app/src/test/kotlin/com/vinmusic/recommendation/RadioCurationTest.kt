package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the autoplay fast-path curation ([RecommendationManager.curateRadioCandidates])
 * and shelf interleaving ([RecommendationManager.interleaveRoundRobin]).
 *
 * curateRadioCandidates decides what plays right after a song when the radio queue
 * is built — before the ranked Smart Queue replaces it.
 */
class RadioCurationTest {

    private fun song(
        id: String,
        title: String,
        author: String,
        durationText: String = "3:21"
    ) = VideoItem(videoId = id, title = title, author = author, durationText = durationText)

    // ═══════════════════════════════════════════════════════════════════════
    // curateRadioCandidates — filtering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `drops compilation mixes and long tracks`() {
        val seed = song("seed", "Seed Song", "Arijit Singh")
        val candidates = listOf(
            song("c1", "Top 50 Hindi Songs Jukebox", "T-Series", "1:02:33"),
            song("c2", "Best Of Arijit Singh", "T-Series", "45:10"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `drops non music videos`() {
        val seed = song("seed", "Seed Song", "Drake")
        val candidates = listOf(
            song("c1", "Seed Song REACTION video", "SomeGuy"),
            song("c2", "How to play Seed Song - Guitar Lesson", "Lessons"),
            song("c3", "Different Song", "Drake"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertEquals(listOf("c3"), result.map { it.videoId })
    }

    @Test
    fun `drops unofficial content like slowed reverb and nightcore`() {
        val seed = song("seed", "Seed Song", "Kendrick Lamar")
        val candidates = listOf(
            song("c1", "Other Song Slowed Reverb", "fan channel"),
            song("c2", "Other Song Nightcore", "someone"),
            // Real official release: curateRadioCandidates now hard-requires an official channel,
            // so the survivor fixture must be a genuine verified uploader.
            song("c3", "Real Similar Song", "Baby Keem - Topic"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertEquals(listOf("c3"), result.map { it.videoId })
    }

    @Test
    fun `excludes the seed itself`() {
        val seed = song("seed", "Seed Song", "Drake")
        val result = RecommendationManager.curateRadioCandidates(seed, listOf(seed))
        assertTrue(result.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // curateRadioCandidates — artist caps & dedupe
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `caps seed artist at one track`() {
        val seed = song("seed", "Seed Song", "Drake")
        val candidates = listOf(
            song("c1", "First Drake Track", "Drake"),
            song("c2", "Second Drake Track", "Drake"),
            song("c3", "Other Artist Track", "Future"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertEquals(listOf("c1", "c3"), result.map { it.videoId })
    }

    @Test
    fun `caps other artists at one track each`() {
        val seed = song("seed", "Seed Song", "J. Cole")
        val candidates = listOf(
            song("c1", "Track One", "Drake"),
            song("c2", "Track Two", "Drake"),
            song("c3", "Track Three", "Drake"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        // Radio quality rule: never repeat an artist back-to-back in the queue.
        assertEquals(1, result.size)
    }

    @Test
    fun `removes near duplicate uploads of the same song`() {
        val seed = song("seed", "Seed Song", "Drake")
        val candidates = listOf(
            song("c1", "Similar Song Official Audio", "Future"),
            song("c2", "Similar Song (Video)", "Future"),
            song("c3", "Totally Different Title", "Travis Scott"),
        )
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertEquals(listOf("c1", "c3"), result.map { it.videoId })
    }

    @Test
    fun `respects maxItems limit`() {
        val seed = song("seed", "Seed Song", "J. Cole")
        // Official-channel fixtures: the official-only gate rejects unknown third-party
        // uploaders, so each candidate must look like a verified release.
        val candidates = (1..12).map { i ->
            song("c$i", "Unique Song Number $i", "Artist $i - Topic")
        }
        val result = RecommendationManager.curateRadioCandidates(seed, candidates, maxItems = 5)
        assertEquals(5, result.size)
    }

    @Test
    fun `blank titles are dropped`() {
        val seed = song("seed", "Seed Song", "Drake")
        val candidates = listOf(song("c1", "", "Drake"), song("c2", "Good Song", "Future"))
        val result = RecommendationManager.curateRadioCandidates(seed, candidates)
        assertEquals(listOf("c2"), result.map { it.videoId })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // interleaveRoundRobin
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `interleaves seeds round robin so none dominates`() {
        val a = listOf(song("a1", "A One", "A"), song("a2", "A Two", "A"), song("a3", "A Three", "A"))
        val b = listOf(song("b1", "B One", "B"), song("b2", "B Two", "B"))
        val merged = RecommendationManager.interleaveRoundRobin(listOf(a, b), cap = 10)
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), merged.map { it.videoId })
    }

    @Test
    fun `interleave respects cap`() {
        val a = (1..5).map { song("a$it", "A $it", "A") }
        val b = (1..5).map { song("b$it", "B $it", "B") }
        val merged = RecommendationManager.interleaveRoundRobin(listOf(a, b), cap = 6)
        assertEquals(6, merged.size)
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "b3"), merged.map { it.videoId })
    }

    @Test
    fun `interleave handles empty lists`() {
        val a = listOf(song("a1", "A One", "A"))
        val merged = RecommendationManager.interleaveRoundRobin(listOf(emptyList(), a, emptyList()), cap = 10)
        assertEquals(listOf("a1"), merged.map { it.videoId })
        assertEquals(0, RecommendationManager.interleaveRoundRobin(emptyList(), cap = 5).size)
    }
}
