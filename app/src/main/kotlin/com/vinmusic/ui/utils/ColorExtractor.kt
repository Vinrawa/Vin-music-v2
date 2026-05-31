package com.vinmusic.ui.utils

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColorExtractor {
    data class MusicPalette(
        val gradTop: Color,
        val gradMid: Color,
        val gradBottom: Color,
        val accent: Color
    )

    suspend fun extractColorsFromUrl(context: Context, url: String): MusicPalette {
        try {
            // Retrieve imageLoader via Coil 3 Context extension property
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(url)
                // Disable hardware bitmaps so the CPU can read pixels for the Palette API
                .allowHardware(false)
                .build()
            // Perform the network/decoding and bitmap pixel access off the main thread
            val result = withContext(Dispatchers.IO) { loader.execute(request) }
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                // Palette generation can be CPU-bound; run on Default dispatcher
                return withContext(Dispatchers.Default) { extractColorsFromBitmap(bitmap) }
            }
        } catch (e: Exception) {
            android.util.Log.e("ColorExtractor", "Failed to extract palette", e)
        }
        
        // Fallback default theme colors matching premium purple theme
        return MusicPalette(
            gradTop = Color(0x336338EC),     // Soft royal purple
            gradMid = Color(0x1F6338EC),     // Deep royal purple
            gradBottom = Color(0xFF0E0E11),  // Dark charcoal background
            accent = Color(0xFF6338EC)       // Vibrant purple accent
        )
    }

    fun extractColorsFromBitmap(bitmap: Bitmap): MusicPalette {
        val palette = Palette.from(bitmap).generate()
        
        // Extract key swatches
        val vibrant = palette.getVibrantColor(
            palette.getDominantColor(0xFF6338EC.toInt())
        )
        val dominant = palette.getDominantColor(0xFF0E0E11.toInt())
        val darkMuted = palette.getDarkMutedColor(0xFF262135.toInt())

        // Calculate beautiful high-fidelity container gradient
        // Ensure dark-theme friendliness by using custom alpha levels
        val baseAccent = Color(vibrant)
        
        return MusicPalette(
            gradTop = Color(darkMuted).copy(alpha = 0.35f),
            gradMid = Color(dominant).copy(alpha = 0.20f),
            gradBottom = Color(0xFF0E0E11), // Rich dark baseline
            accent = baseAccent
        )
    }
}
