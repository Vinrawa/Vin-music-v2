package com.vinmusic.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun CanvasVideoBackground(
    videoUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val videoPlayer = remember(videoUrl) {
        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .apply {
                if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                    setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                } else if (videoUrl.contains(".mp4", ignoreCase = true)) {
                    setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                }
            }
            .build()

        ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f // Muted so it does not interfere with audio playback
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.w("CanvasVideoBackground", "Canvas playback warning: ${error.message}")
                }
            })
            prepare()
            playWhenReady = isPlaying
        }
    }

    LaunchedEffect(videoPlayer, isPlaying) {
        if (isPlaying) {
            videoPlayer.play()
        } else {
            videoPlayer.pause()
        }
    }

    DisposableEffect(videoPlayer) {
        onDispose {
            try {
                videoPlayer.stop()
                videoPlayer.release()
            } catch (_: Exception) {}
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = videoPlayer
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { playerView ->
            if (playerView.player != videoPlayer) {
                playerView.player = videoPlayer
            }
        },
        modifier = modifier
    )
}
