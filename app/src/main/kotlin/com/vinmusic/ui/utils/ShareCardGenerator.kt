package com.vinmusic.ui.utils

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates a beautiful shareable song card image with album art, song details,
 * and Vin Music branding — then launches a share intent.
 */
object ShareCardGenerator {

    private const val CARD_W = 1080
    private const val CARD_H = 1920

    /**
     * Generate a premium song card and share it.
     */
    suspend fun generateAndShare(
        context: Context,
        songTitle: String,
        artistName: String,
        thumbnailUrl: String,
        duration: String
    ) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                generateCard(context, songTitle, artistName, thumbnailUrl, duration)
            }
            withContext(Dispatchers.Main) {
                shareImage(context, bitmap, songTitle, artistName)
            }
        } catch (e: Exception) {
            android.util.Log.e("ShareCard", "Error generating or sharing card", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun generateCard(
        context: Context,
        title: String,
        artist: String,
        thumbnailUrl: String,
        duration: String
    ): Bitmap {
        // Load album art
        val albumArt = loadBitmap(context, thumbnailUrl)

        val card = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)

        // ── 1. Dark gradient background ──
        val bgPaint = Paint()
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, CARD_H.toFloat(),
            intArrayOf(0xFF1A1025.toInt(), 0xFF0E0E11.toInt(), 0xFF0A0A0D.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), bgPaint)

        // ── 2. Blurred album art as ambient backdrop ──
        if (albumArt != null) {
            try {
                val blurredArt = scaledBlur(albumArt, 25)
                val artPaint = Paint().apply { alpha = 80 }
                val srcRect = Rect(0, 0, blurredArt.width, blurredArt.height)
                val dstRect = Rect(0, 0, CARD_W, CARD_H)
                canvas.drawBitmap(blurredArt, srcRect, dstRect, artPaint)
                blurredArt.recycle()
            } catch (e: Exception) {
                android.util.Log.e("ShareCard", "Failed to draw blurred backdrop", e)
            }
        }

        // ── 3. Subtle vignette overlay ──
        val vignette = Paint()
        vignette.shader = RadialGradient(
            CARD_W / 2f, CARD_H * 0.4f,
            CARD_W * 0.9f,
            intArrayOf(0x00000000, 0x80000000.toInt()),
            floatArrayOf(0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), vignette)

        // ── 4. Album art (rounded square, centered) ──
        val artSize = 620
        val artLeft = (CARD_W - artSize) / 2f
        val artTop = 340f
        val artRound = 40f

        if (albumArt != null) {
            try {
                val scaled = Bitmap.createScaledBitmap(albumArt, artSize, artSize, true)
                val rounded = getRoundedBitmap(scaled, artRound)
                
                // Glow shadow behind art
                val glowPaint = Paint().apply {
                    maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
                    color = 0x556338EC.toInt()
                }
                canvas.drawRoundRect(
                    artLeft - 10f, artTop - 10f,
                    artLeft + artSize + 10f, artTop + artSize + 10f,
                    artRound, artRound, glowPaint
                )
                
                canvas.drawBitmap(rounded, artLeft, artTop, null)
                scaled.recycle()
                rounded.recycle()
            } catch (e: Exception) {
                android.util.Log.e("ShareCard", "Failed to draw album art", e)
                // Draw a fallback colored placeholder card
                val placeholderPaint = Paint().apply { color = 0xFF6338EC.toInt() }
                canvas.drawRoundRect(artLeft, artTop, artLeft + artSize, artTop + artSize, artRound, artRound, placeholderPaint)
            }
        } else {
            // Draw a fallback colored placeholder card
            val placeholderPaint = Paint().apply { color = 0xFF6338EC.toInt() }
            canvas.drawRoundRect(artLeft, artTop, artLeft + artSize, artTop + artSize, artRound, artRound, placeholderPaint)
        }

        // ── 5. Song Title ──
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val titleY = artTop + artSize + 100f
        drawWrappedText(canvas, title, CARD_W / 2f, titleY, titlePaint, CARD_W - 120)

        // ── 6. Artist Name ──
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBBFFFFFF.toInt()
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val linesUsed = countWrappedLines(title, titlePaint, CARD_W - 120)
        val artistY = titleY + (linesUsed * 68f) + 30f
        canvas.drawText(artist, CARD_W / 2f, artistY, artistPaint)

        // ── 7. Duration pill ──
        val durationY = artistY + 70f
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6338EC.toInt()
        }
        val durationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val pillW = durationTextPaint.measureText(duration) + 60f
        canvas.drawRoundRect(
            CARD_W / 2f - pillW / 2f, durationY - 28f,
            CARD_W / 2f + pillW / 2f, durationY + 18f,
            24f, 24f, pillPaint
        )
        canvas.drawText(duration, CARD_W / 2f, durationY + 6f, durationTextPaint)

        // ── 8. "Now Playing on" label ──
        val nowPlayingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFFFFF.toInt()
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.15f
        }
        canvas.drawText("NOW PLAYING ON", CARD_W / 2f, CARD_H - 200f, nowPlayingPaint)

        // ── 9. Vin Music branding ──
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }
        canvas.drawText("Vin Music", CARD_W / 2f, CARD_H - 140f, brandPaint)

        // ── 10. Decorative accent line at top ──
        val accentPaint = Paint().apply {
            shader = LinearGradient(
                CARD_W * 0.2f, 0f, CARD_W * 0.8f, 0f,
                intArrayOf(0x006338EC, 0xFF6338EC.toInt(), 0x006338EC),
                null, Shader.TileMode.CLAMP
            )
            strokeWidth = 4f
        }
        canvas.drawLine(CARD_W * 0.2f, 20f, CARD_W * 0.8f, 20f, accentPaint)

        albumArt?.recycle()
        return card
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false) // Required to make sure we can read/modify the bitmap pixels
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                result.image.toBitmap()
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ShareCard", "Failed to load album art bitmap from: $url", e)
            null
        }
    }

    private fun scaledBlur(src: Bitmap, radius: Int): Bitmap {
        val scale = 0.1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true).also {
            small.recycle()
        }
    }

    private fun getRoundedBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Int
    ) {
        val words = text.split(" ")
        var line = ""
        var cy = y
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, cy, paint)
                cy += 68f
                line = word
            } else {
                line = test
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line, x, cy, paint)
    }

    private fun countWrappedLines(text: String, paint: Paint, maxWidth: Int): Int {
        val words = text.split(" ")
        var line = ""
        var count = 1
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                count++
                line = word
            } else {
                line = test
            }
        }
        return count
    }

    private fun shareImage(context: Context, bitmap: Bitmap, title: String, artist: String) {
        try {
            // Save to cache dir
            val shareDir = File(context.cacheDir, "share_cards")
            shareDir.mkdirs()
            val file = File(shareDir, "vin_music_card.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Listening to \"$title\" by $artist on Vin Music!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooserIntent = Intent.createChooser(shareIntent, "Share Song Card").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            android.util.Log.e("ShareCard", "Failed to share: ${e.message}", e)
            Toast.makeText(context, "Failed to share image: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            bitmap.recycle()
        }
    }
}
