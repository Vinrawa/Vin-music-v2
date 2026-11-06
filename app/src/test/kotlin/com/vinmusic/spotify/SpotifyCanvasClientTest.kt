package com.vinmusic.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyCanvasClientTest {

    @Test
    fun `normalize strips video and official noise correctly`() {
        val raw = "Flowers (Official Music Video)"
        val normalized = SpotifyCanvasClient.normalize(raw)
        assertEquals("flowers", normalized)
    }

    @Test
    fun `normalize strips YouTube pipe metadata correctly`() {
        val raw = "Channa Mereya | Ae Dil Hai Mushkil | Pritam | Arijit Singh"
        val normalized = SpotifyCanvasClient.normalize(raw)
        assertEquals("channa mereya", normalized)
    }

    @Test
    fun `normalize strips trailing hyphen video markers`() {
        val raw = "Out of Time - Official Video"
        val normalized = SpotifyCanvasClient.normalize(raw)
        assertEquals("out of time", normalized)
    }

    @Test
    fun `extractBaseTitle gets cleanly stripped title`() {
        val raw = "Calling (Spider-Man: Across the Spider-Verse)"
        val base = SpotifyCanvasClient.extractBaseTitle(raw)
        assertEquals("calling", base)
    }

    @Test
    fun `normalize handles simple song titles intact`() {
        val raw = "Dracula"
        val normalized = SpotifyCanvasClient.normalize(raw)
        assertEquals("dracula", normalized)
    }
}