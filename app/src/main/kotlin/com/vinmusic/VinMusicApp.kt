package com.vinmusic

import android.app.Application
import com.vinmusic.innertube.NewPipeInit
import com.vinmusic.innertube.YTMusicApi
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VinMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipeInit.init()
        YTMusicApi.attachContext(this)
    }
}
