package com.vinmusic.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vinmusic.MainActivity
import dagger.hilt.android.AndroidEntryPoint

import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@AndroidEntryPoint
@UnstableApi
class VinMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("VIN_SERVICE", "VinMusicService created")

        val player = PlayerSingleton.getOrCreate(applicationContext)

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
            override fun seekToNextMediaItem() { PlayerSingleton.actionEvents.tryEmit("NEXT") }
            override fun seekToNext() { PlayerSingleton.actionEvents.tryEmit("NEXT") }
            override fun seekToPreviousMediaItem() { PlayerSingleton.actionEvents.tryEmit("PREV") }
            override fun seekToPrevious() { PlayerSingleton.actionEvents.tryEmit("PREV") }
        }

        // PendingIntent to reopen app when notification is tapped
        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val likeButton = CommandButton.Builder()
            .setDisplayName("Like")
            .setIconResId(com.vinmusic.R.drawable.ic_unlike)
            .setSessionCommand(SessionCommand("ACTION_LIKE", Bundle.EMPTY))
            .build()

        val repeatButton = CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(com.vinmusic.R.drawable.ic_repeat)
            .setSessionCommand(SessionCommand("ACTION_REPEAT", Bundle.EMPTY))
            .build()

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand("ACTION_LIKE", Bundle.EMPTY))
                    .add(SessionCommand("ACTION_REPEAT", Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    connectionResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == "ACTION_LIKE") {
                    PlayerSingleton.actionEvents.tryEmit("LIKE")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == "ACTION_REPEAT") {
                    PlayerSingleton.actionEvents.tryEmit("REPEAT")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(activityIntent)
            .setCallback(callback)
            .setCustomLayout(listOf(likeButton, repeatButton))
            .build()

        // Provide the custom notification provider to ensure small icon is set
        val provider = androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build()
        provider.setSmallIcon(com.vinmusic.R.drawable.media3_notification_small_icon)
        setMediaNotificationProvider(provider)

        PlayerSingleton.mediaSession = mediaSession
        Log.d("VIN_SERVICE", "MediaSession created, notification will appear")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        Log.d("VIN_SERVICE", "VinMusicService destroyed")
        mediaSession?.run {
            // Don't release player here — ViewModel owns its lifecycle
            release()
        }
        mediaSession = null
        PlayerSingleton.mediaSession = null
        super.onDestroy()
    }
}
