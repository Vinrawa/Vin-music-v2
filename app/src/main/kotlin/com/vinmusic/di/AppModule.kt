package com.vinmusic.di

import android.content.Context
import com.vinmusic.data.db.VinDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): VinDatabase =
        VinDatabase.getInstance(ctx)

    @Provides
    fun provideLikedSongDao(db: VinDatabase) = db.likedSongDao()

    @Provides
    fun provideHistoryDao(db: VinDatabase) = db.historyDao()

    @Provides
    fun provideDownloadDao(db: VinDatabase) = db.downloadDao()

    @Provides
    fun providePlaylistDao(db: VinDatabase) = db.playlistDao()

    @Provides
    fun provideQueueDao(db: VinDatabase) = db.queueDao()

    @Provides
    @Singleton
    fun provideRecommendationRepository(
        @ApplicationContext ctx: Context,
        db: VinDatabase
    ): com.vinmusic.recommendation.RecommendationRepository =
        com.vinmusic.recommendation.RecommendationRepository(ctx, db)
}
