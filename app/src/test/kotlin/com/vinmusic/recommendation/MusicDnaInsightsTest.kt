package com.vinmusic.recommendation

import com.vinmusic.data.db.InteractionSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDnaInsightsTest {

    @Test
    fun `compares the recent era against the complete local listening profile`() {
        val now = 1_800_000_000_000L
        val coreSong = signal("core", energy = 40, tempo = 100, lastPlayedAt = now - 20L * DAY)
        val recentSong = signal("recent", energy = 80, tempo = 140, lastPlayedAt = now - DAY)

        val result = MusicDnaInsights.build(listOf(coreSong, recentSong), now)!!

        assertEquals(2, result.core.trackCount)
        assertEquals(1, result.currentEra!!.trackCount)
        assertEquals(80, result.currentEra.profile.energy)
        assertTrue(result.currentEra.profile.energy > result.core.profile.energy)
    }

    @Test
    fun `uses learning confidence until enough real interactions exist`() {
        val result = MusicDnaInsights.build(listOf(signal("one", playCount = 2)), 1_800_000_000_000L)!!

        assertEquals(MusicDnaComparison.Confidence.LEARNING, result.confidence)
    }

    @Test
    fun `does not invent a recent era for old listening data`() {
        val now = 1_800_000_000_000L
        val result = MusicDnaInsights.build(
            listOf(signal("old", lastPlayedAt = now - 30L * DAY)),
            now
        )!!

        assertNull(result.currentEra)
    }

    private fun signal(
        id: String,
        energy: Int = 60,
        tempo: Int = 120,
        playCount: Int = 1,
        lastPlayedAt: Long = 1_800_000_000_000L
    ) = InteractionSignal(
        videoId = id,
        title = id,
        author = "Artist",
        durationText = "3:00",
        playCount = playCount,
        completeCount = 1,
        energy = energy,
        valence = 55,
        danceability = 65,
        acousticness = 30,
        tempo = tempo,
        lastPlayedAt = lastPlayedAt
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
