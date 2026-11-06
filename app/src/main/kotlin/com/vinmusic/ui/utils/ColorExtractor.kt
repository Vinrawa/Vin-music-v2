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
import kotlin.math.abs
import kotlin.math.min

object ColorExtractor {
    data class MusicPalette(
        val gradTop: Color,
        val gradMid: Color,
        val gradBottom: Color,
        val accent: Color,
        val dominant: Color = accent,
        val secondary: Color = accent
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
            .maximumColorCount(32)
            .generate()

        val allSwatches = palette.swatches
        if (allSwatches.isEmpty()) return fallbackPalette()

        // ── 1. Monochrome / Grayscale Art Detection ──
        // If max saturation across all extracted swatches is < 0.10, this is pure B&W/grayscale art.
        // Never force artificial saturation which causes dirty olive-brown/muddy tints!
        val maxSaturation = allSwatches.maxOfOrNull { swatch ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(swatch.rgb, hsv)
            hsv[1]
        } ?: 0f

        if (maxSaturation < 0.10f) {
            // Elegant modern Platinum Monochrome theme
            val platinumAccent = Color(0xFFE4E4E7) // Zinc-200
            val platinumSecondary = Color(0xFFA1A1AA) // Zinc-400
            val darkSlateDominant = Color(0xFF27272A) // Zinc-800
            return MusicPalette(
                gradTop = platinumAccent.copy(alpha = 0.28f),
                gradMid = platinumSecondary.copy(alpha = 0.16f),
                gradBottom = Color(0xFF0E0E11),
                accent = platinumAccent,
                dominant = darkSlateDominant,
                secondary = platinumSecondary
            )
        }

        // ── 2. Aesthetic Vibrancy Scoring for Hero Accent ──
        val maxPopulation = allSwatches.maxOf { it.population }.coerceAtLeast(1).toFloat()

        // Candidate swatches excluding near-pitch-black or blown-out white
        val scoredSwatches = allSwatches.mapNotNull { swatch ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(swatch.rgb, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            // Reject swatches with near-pitch-black, blown-out white, or murky dark grays
            if (v < 0.15f || (v > 0.95f && s < 0.12f)) return@mapNotNull null
            if (s < 0.15f && v < 0.40f) return@mapNotNull null

            // Square-root population weight gives large color areas (like skies, backdrops, neon lights)
            // strong presence over tiny skin or compression artifacts
            val popScore = kotlin.math.sqrt((swatch.population.toFloat() / maxPopulation).toDouble()).toFloat().coerceIn(0f, 1f)

            // Vibrancy-dominant aesthetic score
            val totalScore = (s * 0.48f) + (popScore * 0.38f) + (v.coerceAtMost(0.75f) * 0.14f)
            Triple(swatch, hsv, totalScore)
        }.sortedByDescending { it.third }

        val heroEntry = scoredSwatches.firstOrNull() ?: run {
            // Fallback to highest vibrancy swatch
            val fallbackSwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
            val hsv = FloatArray(3)
            if (fallbackSwatch != null) android.graphics.Color.colorToHSV(fallbackSwatch.rgb, hsv)
            Triple(fallbackSwatch ?: allSwatches.first(), hsv, 0f)
        }

        val heroAccent = Color(heroEntry.first.rgb).boostForUi()
        val heroHue = heroEntry.second[0]

        // ── 3. Harmonic Secondary Color Selection ──
        // Find a distinct swatch with at least 35° hue difference from hero
        val secondaryEntry = scoredSwatches.firstOrNull { candidate ->
            val candidateHue = candidate.second[0]
            val hueDiff = min(abs(heroHue - candidateHue), 360f - abs(heroHue - candidateHue))
            hueDiff >= 35f && candidate.second[1] >= 0.18f
        }

        val secondaryColor = if (secondaryEntry != null) {
            Color(secondaryEntry.first.rgb).boostForUi()
        } else {
            // Analogous harmony generation: rotate hue by +32° with pleasing saturation
            val harmonicHsv = FloatArray(3).apply {
                this[0] = (heroHue + 32f) % 360f
                this[1] = (heroEntry.second[1] * 0.85f).coerceIn(0.35f, 0.85f)
                this[2] = (heroEntry.second[2] * 0.90f).coerceIn(0.45f, 0.85f)
            }
            Color(android.graphics.Color.HSVToColor(harmonicHsv))
        }

        // ── 4. Dominant Color Selection ──
        val dominantSwatch = palette.dominantSwatch
        val dominantColor = if (dominantSwatch != null) {
            val domHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(dominantSwatch.rgb, domHsv)
            if (domHsv[2] < 0.15f) {
                // If dominant is pitch black, create deep shade from hero accent
                val deepHsv = FloatArray(3).apply {
                    this[0] = heroHue
                    this[1] = (heroEntry.second[1] * 0.7f).coerceIn(0.3f, 0.7f)
                    this[2] = 0.32f
                }
                Color(android.graphics.Color.HSVToColor(deepHsv))
            } else {
                Color(dominantSwatch.rgb).boostForUi()
            }
        } else {
            heroAccent
        }

        return MusicPalette(
            gradTop = heroAccent.copy(alpha = 0.32f),
            gradMid = secondaryColor.copy(alpha = 0.20f),
            gradBottom = Color(0xFF0E0E11),
            accent = heroAccent,
            dominant = dominantColor,
            secondary = secondaryColor
        )
    }

    private fun Color.boostForUi(): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb(
            (red * 255).toInt().coerceIn(0, 255),
            (green * 255).toInt().coerceIn(0, 255),
            (blue * 255).toInt().coerceIn(0, 255)
        ), hsv)
        if (hsv[1] >= 0.08f) {
            hsv[1] = hsv[1].coerceAtLeast(0.40f)
        }
        hsv[2] = hsv[2].coerceIn(0.68f, 0.92f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun fallbackPalette(): MusicPalette {
        val accent = Color(0xFF6EA8FF)
        val dominant = Color(0xFF1E3A8A)
        val secondary = Color(0xFF7C3AED)
        return MusicPalette(
            gradTop = accent.copy(alpha = 0.28f),
            gradMid = secondary.copy(alpha = 0.18f),
            gradBottom = Color(0xFF0E0E11),
            accent = accent,
            dominant = dominant,
            secondary = secondary
        )
    }
}
