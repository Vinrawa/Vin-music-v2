package com.vinmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowConversionToBitmap
import com.vinmusic.MainActivity
import com.vinmusic.R
import com.vinmusic.player.PlayerSingleton
import coil3.toBitmap
import coil3.request.allowHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.vinmusic.widget.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT       = "com.vinmusic.widget.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS   = "com.vinmusic.widget.ACTION_WIDGET_PREVIOUS"

        private val coroutineScope = CoroutineScope(Dispatchers.Main)

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, MusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, MusicWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                context.sendBroadcast(intent)
            }
        }

        private fun getRoundedCornerBitmap(bitmap: Bitmap, pixels: Float): Bitmap {
            var softwareBitmap = bitmap
            try {
                if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
                    val copied = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (copied != null) {
                        softwareBitmap = copied
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicWidgetProvider", "Failed to copy bitmap: ${e.message}")
            }

            try {
                val output = Bitmap.createBitmap(softwareBitmap.width, softwareBitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint().apply { isAntiAlias = true }
                val rect = Rect(0, 0, softwareBitmap.width, softwareBitmap.height)
                val rectF = RectF(rect)
                canvas.drawARGB(0, 0, 0, 0)
                canvas.drawRoundRect(rectF, pixels, pixels, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(softwareBitmap, rect, rect, paint)
                return output
            } catch (e: Exception) {
                android.util.Log.e("MusicWidgetProvider", "Failed to draw rounded corner bitmap, returning fallback: ${e.message}")
                return softwareBitmap
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.music_widget)

        // Bind playback control button PendingIntents
        val playPauseIntent = Intent(context, MusicWidgetProvider::class.java).apply { action = ACTION_WIDGET_PLAY_PAUSE }
        val nextIntent      = Intent(context, MusicWidgetProvider::class.java).apply { action = ACTION_WIDGET_NEXT }
        val prevIntent      = Intent(context, MusicWidgetProvider::class.java).apply { action = ACTION_WIDGET_PREVIOUS }
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val playPausePendingIntent = PendingIntent.getBroadcast(context, 1, playPauseIntent, flag)
        val nextPendingIntent      = PendingIntent.getBroadcast(context, 2, nextIntent, flag)
        val prevPendingIntent      = PendingIntent.getBroadcast(context, 3, prevIntent, flag)
        val mainPendingIntent      = PendingIntent.getActivity(context, 4, mainActivityIntent, flag)

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, playPausePendingIntent)
        views.setOnClickPendingIntent(R.id.widget_btn_next, nextPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_btn_prev, prevPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_cover_art, mainPendingIntent)

        // Bind song details
        val currentSong = PlayerSingleton.currentSong
        val isPlaying   = PlayerSingleton.isPlaying

        if (currentSong != null) {
            views.setTextViewText(R.id.widget_title, currentSong.title)
            views.setTextViewText(R.id.widget_artist, currentSong.author)
            views.setImageViewResource(
                R.id.widget_btn_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            // Async load cover artwork from cached URL using Coil
            val coverUrl = "https://i.ytimg.com/vi/${currentSong.videoId}/hqdefault.jpg"
            coroutineScope.launch {
                val loadedBitmap = withContext(Dispatchers.IO) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(coverUrl)
                            .allowConversionToBitmap(true)
                            .allowHardware(false)
                            .build()
                        val result = ImageLoader(context).execute(request)
                        if (result is SuccessResult) {
                            result.image.toBitmap()
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
                if (loadedBitmap != null) {
                    val roundedBitmap = getRoundedCornerBitmap(loadedBitmap, 24f)
                    views.setImageViewBitmap(R.id.widget_cover_art, roundedBitmap)
                } else {
                    views.setImageViewResource(R.id.widget_cover_art, R.drawable.media3_notification_small_icon)
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        } else {
            views.setTextViewText(R.id.widget_title, "Not Playing")
            views.setTextViewText(R.id.widget_artist, "Tap to stream music")
            views.setImageViewResource(R.id.widget_btn_play_pause, android.R.drawable.ic_media_play)
            views.setImageViewResource(R.id.widget_cover_art, R.drawable.media3_notification_small_icon)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_PLAY_PAUSE -> {
                PlayerSingleton.togglePlay()
                updateAllWidgets(context)
            }
            ACTION_WIDGET_NEXT -> {
                PlayerSingleton.playNext()
                updateAllWidgets(context)
            }
            ACTION_WIDGET_PREVIOUS -> {
                PlayerSingleton.playPrev()
                updateAllWidgets(context)
            }
        }
    }
}
