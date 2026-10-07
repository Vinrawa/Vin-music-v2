package com.vinmusic

import android.app.Application
import com.vinmusic.innertube.NewPipeInit
import com.vinmusic.innertube.YTMusicApi
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade

import io.sentry.android.core.SentryAndroid
import com.vinmusic.diagnostics.ReliabilityDiagnostics

@HiltAndroidApp
class VinMusicApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Sentry with custom runtime options
        SentryAndroid.init(this) { options ->
            options.isEnableUserInteractionTracing = true
            options.isEnableUserInteractionBreadcrumbs = true
        }

        NewPipeInit.init()
        com.vinmusic.innertube.InnerTube.init(this)
        com.vinmusic.data.ArtistDataCache.init(this)
        YTMusicApi.attachContext(this)
        com.vinmusic.config.RemoteConfigHelper.init()
        ReliabilityDiagnostics.init(this)

        // Warm up ExoPlayer caches and Recommendation DB asynchronously on Dispatchers.IO to eliminate Main thread disk scans
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.vinmusic.player.PlayerSingleton.getCache(applicationContext)
            com.vinmusic.player.PlayerSingleton.getDownloadCache(applicationContext)
            try {
                com.vinmusic.recommendation.RecommendationDatabase.getInstance(applicationContext).openHelper.writableDatabase
                com.vinmusic.recommendation.RecommendationManager.loadGenreGraph(applicationContext)
            } catch (_: Exception) {}
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // Use 25% of RAM for cached album covers
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // Use up to 2% of disk space for persistent covers
                    .build()
            }
            .crossfade(true) // Smooth 100ms transitions
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // When app is hidden or OS is under memory pressure, purge Coil's in-memory bitmap
        // cache so Android's Low Memory Killer (LMK) doesn't kill the background playback process.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try {
                SingletonImageLoader.get(this).memoryCache?.clear()
            } catch (_: Exception) {}
        }
    }
}
