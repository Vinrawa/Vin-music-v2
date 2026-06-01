package com.vinmusic

import android.app.Application
import com.vinmusic.innertube.NewPipeInit
import com.vinmusic.innertube.YTMusicApi
import dagger.hilt.android.HiltAndroidApp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade

@HiltAndroidApp
class VinMusicApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        NewPipeInit.init()
        YTMusicApi.attachContext(this)
        com.vinmusic.config.RemoteConfigHelper.init()
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
}
