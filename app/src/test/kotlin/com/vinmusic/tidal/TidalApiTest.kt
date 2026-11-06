package com.vinmusic.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalApiTest {

    @Test
    fun testGetCoverUrlFormatsCorrectly() {
        val uuid = "d0612575-d361-4b08-bb26-5fedb1461269"
        val expected = "https://resources.tidal.com/images/d0612575/d361/4b08/bb26/5fedb1461269/1280x1280.jpg"
        val actual = TidalApi.getCoverUrl(uuid, "1280x1280")
        assertEquals(expected, actual)
    }

    @Test
    fun testGetCoverUrlHandlesNullOrBlank() {
        assertEquals(null, TidalApi.getCoverUrl(null))
        assertEquals(null, TidalApi.getCoverUrl(""))
        assertEquals(null, TidalApi.getCoverUrl("   "))
    }
}
