package com.vinmusic.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTotpTest {

    @Test
    fun `generateTotp produces exact expected 6 digit code for known secret vector`() {
        val secret61 = intArrayOf(
            44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78
        )
        val timestamp = 1793914948L
        val code = SpotifySession.generateTotp(timestamp, secret61)

        assertEquals("564629", code)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `generateTotp produces zero padded 6 digit code`() {
        val secret59 = intArrayOf(
            123, 105, 79, 70, 110, 59, 52, 125, 60, 49, 80, 70, 89, 75, 80, 86, 63, 53, 123, 37, 117, 49, 52, 93, 77, 62, 47, 86, 48, 104, 68, 72
        )
        val timestamp = 1793914948L
        val code = SpotifySession.generateTotp(timestamp, secret59)

        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }
}
