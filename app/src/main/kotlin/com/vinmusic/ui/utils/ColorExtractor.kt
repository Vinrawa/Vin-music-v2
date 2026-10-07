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
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val bitmap = result?.image?.toBitmap()
                if (bitmap != null) extractColorsFromBitmap(bitmap) else fallbackPalette()
            } catch (_: Exception) {
                fallbackPalette()
            }
        }
    }

    fun extractColorsFromBitmap(bitmap: Bitmap): MusicPalette {
        val palette = Palette.from(bitmap)
            .maximumColorCount(24)
            .generate()
        val accent = pickCoverAccent(palette)
        return MusicPalette(
            gradTop = accent.copy(alpha = 0.20f),
            gradMid = accent.copy(alpha = 0.12f),
            gradBottom = Color(0xFF0E0E11),
            accent = accent
        )
    }

    private fun pickCoverAccent(palette: Palette): Color {
        val candidates = listOfNotNull(
            palette.dominantSwatch,
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.darkVibrantSwatch,
            palette.mutedSwatch,
            palette.lightMutedSwatch,
            palette.darkMutedSwatch
        )
        val swatch = candidates.firstOrNull { swatch ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(swatch.rgb, hsv)
            hsv[1] >= 0.18f && hsv[2] in 0.22f..0.92f
        } ?: candidates.firstOrNull()

        return swatch?.rgb?.let { Color(it).boostForUi() } ?: fallbackPalette().accent
    }

    private fun Color.boostForUi(): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb(
            (red * 255).toInt().coerceIn(0, 255),
            (green * 255).toInt().coerceIn(0, 255),
            (blue * 255).toInt().coerceIn(0, 255)
        ), hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.28f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.88f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun fallbackPalette(): MusicPalette {
        val accent = Color(0xFF6EA8FF)
        return MusicPalette(
            gradTop = accent.copy(alpha = 0.20f),
            gradMid = accent.copy(alpha = 0.12f),
            gradBottom = Color(0xFF0E0E11),
            accent = accent
        )
    }
}
