package com.vinmusic.recommendation

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.Index

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["cluster_id"]),
        Index(value = ["genre"]),
        Index(value = ["artist"])
    ]
)
data class SpotifyTrack(
    @PrimaryKey
    val id: Int? = null,
    val title: String,
    val artist: String,
    val dance: Int,
    val energy: Int,
    val valence: Int,
    val tempo: Int,
    val acoustic: Int,
    val cluster_id: Int,
    @ColumnInfo(defaultValue = "")
    val genre: String = ""
)

@Dao
interface SpotifyTrackDao {
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' LIMIT 1")
    suspend fun findTrack(query: String): SpotifyTrack?

    @Query("SELECT * FROM tracks WHERE title = :title LIMIT 1")
    suspend fun findTrackExact(title: String): SpotifyTrack?
    
    @Query("SELECT * FROM tracks WHERE title LIKE :prefixQuery || '%' OR :prefixQuery LIKE title || '%' LIMIT 50")
    suspend fun findTracksByTitlePrefix(prefixQuery: String): List<SpotifyTrack>
    
    // Fast cluster-based nearest neighbor search
    @Query("""
        SELECT * FROM tracks 
        WHERE cluster_id = :targetCluster
        ORDER BY (
            (energy - :targetEnergy) * (energy - :targetEnergy) + 
            (valence - :targetValence) * (valence - :targetValence) +
            (dance - :targetDance) * (dance - :targetDance) +
            (acoustic - :targetAcoustic) * (acoustic - :targetAcoustic) +
            ((tempo - :targetTempo) * (tempo - :targetTempo) / 4)
        ) ASC 
        LIMIT :limit
    """)
    suspend fun getSimilarTracksInCluster(targetCluster: Int, targetEnergy: Int, targetValence: Int, targetDance: Int, targetAcoustic: Int, targetTempo: Int, limit: Int = 20): List<SpotifyTrack>
    
    // Cluster NN with extra cushion for post-filtering excluded artists
    @Query("""
        SELECT * FROM tracks 
        WHERE cluster_id = :targetCluster
        ORDER BY (
            (energy - :targetEnergy) * (energy - :targetEnergy) + 
            (valence - :targetValence) * (valence - :targetValence) +
            (dance - :targetDance) * (dance - :targetDance) +
            (acoustic - :targetAcoustic) * (acoustic - :targetAcoustic) +
            ((tempo - :targetTempo) * (tempo - :targetTempo) / 4)
        ) ASC 
        LIMIT :limit
    """)
    suspend fun getClusterNeighborsCushioned(targetCluster: Int, targetEnergy: Int, targetValence: Int, targetDance: Int, targetAcoustic: Int, targetTempo: Int, limit: Int = 40): List<SpotifyTrack>

    // Cluster NN with genre filter — only returns tracks in the same genre family
    @Query("""
        SELECT * FROM tracks 
        WHERE cluster_id = :targetCluster AND genre = :genre
        ORDER BY (
            (energy - :targetEnergy) * (energy - :targetEnergy) + 
            (valence - :targetValence) * (valence - :targetValence) +
            (dance - :targetDance) * (dance - :targetDance) +
            (acoustic - :targetAcoustic) * (acoustic - :targetAcoustic) +
            ((tempo - :targetTempo) * (tempo - :targetTempo) / 4)
        ) ASC 
        LIMIT :limit
    """)
    suspend fun getClusterNeighborsByGenre(targetCluster: Int, genre: String, targetEnergy: Int, targetValence: Int, targetDance: Int, targetAcoustic: Int, targetTempo: Int, limit: Int = 40): List<SpotifyTrack>

    // Legacy search for fallback
    @Query("""
        SELECT * FROM tracks 
        ORDER BY (
            (energy - :targetEnergy) * (energy - :targetEnergy) + 
            (valence - :targetValence) * (valence - :targetValence) +
            (dance - :targetDance) * (dance - :targetDance) +
            (acoustic - :targetAcoustic) * (acoustic - :targetAcoustic) +
            ((tempo - :targetTempo) * (tempo - :targetTempo) / 4)
        ) ASC 
        LIMIT :limit
    """)
    suspend fun getSimilarTracks(targetEnergy: Int, targetValence: Int, targetDance: Int, targetAcoustic: Int, targetTempo: Int, limit: Int = 20): List<SpotifyTrack>

    // Find tracks by artist (uses B-Tree index on artist column)
    @Query("SELECT * FROM tracks WHERE artist = :artist COLLATE NOCASE LIMIT 50")
    suspend fun findTracksByArtistExact(artist: String): List<SpotifyTrack>

    @Query("SELECT * FROM tracks WHERE artist LIKE :artistPrefix || '%' LIMIT 50")
    suspend fun findTracksByArtistPrefix(artistPrefix: String): List<SpotifyTrack>

    @Query("SELECT * FROM tracks WHERE artist LIKE '%' || :artist || '%' LIMIT 50")
    suspend fun findTracksByArtist(artist: String): List<SpotifyTrack>

    // Find tracks by genre (uses B-Tree index on genre column)
    @Query("SELECT * FROM tracks WHERE genre = :genre COLLATE NOCASE LIMIT 50")
    suspend fun findTracksByGenreExact(genre: String): List<SpotifyTrack>

    @Query("SELECT * FROM tracks WHERE genre LIKE :genrePrefix || '%' LIMIT 50")
    suspend fun findTracksByGenrePrefix(genrePrefix: String): List<SpotifyTrack>

    @Query("SELECT * FROM tracks WHERE genre LIKE '%' || :genre || '%' LIMIT 50")
    suspend fun findTracksByGenre(genre: String): List<SpotifyTrack>
}

// This is a READ-ONLY reference dataset (Spotify audio features + clusters + genres).
// User data (history, likes, skips, playlists) lives in VinDatabase — NOT here.
// fallbackToDestructiveMigration() is safe: it only replaces the bundled track catalog
// when the version changes. No user data is lost.
@Database(entities = [SpotifyTrack::class], version = 7, exportSchema = false)
abstract class RecommendationDatabase : RoomDatabase() {
    abstract fun trackDao(): SpotifyTrackDao

    companion object {
        @Volatile
        private var INSTANCE: RecommendationDatabase? = null

        fun getInstance(context: Context): RecommendationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecommendationDatabase::class.java,
                    "recommendations.db"
                )
                .createFromAsset("recommendations.db")
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
