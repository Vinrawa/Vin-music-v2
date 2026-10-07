package com.vinmusic.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMetadataTest {

    @Test
    fun `does not turn a release year into a track count`() {
        val item = album("2025 • Album")

        assertNull(item.trackCountText())
        assertEquals("2025 • Album", item.metadataSummary())
    }

    @Test
    fun `extracts only an explicitly labelled track count`() {
        val item = album("2025 • Album • 12 songs")

        assertEquals("12", item.trackCountText())
        assertEquals("12 tracks", item.trackCountLabel())
        assertEquals("2025 • Album • 12 tracks", item.metadataSummary())
    }

    @Test
    fun `recognizes a one track single without relabelling a year`() {
        val item = album("2026 • Single • 1 song")

        assertTrue(item.isSingleRelease())
        assertEquals("1 track", item.trackCountLabel())
        assertFalse(album("2026 • Album").isSingleRelease())
    }

    private fun album(subtitle: String) = AlbumItem(
        playlistId = "PL_test",
        title = "Test release",
        author = "Test artist",
        thumbnail = "",
        songCount = subtitle
    )
}
