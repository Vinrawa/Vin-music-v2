package com.vinmusic.recommendation

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "tracks")
data class SpotifyTrack(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val artist: String,
    val dance: Int,
    val energy: Int,
    val valence: Int,
    val tempo: Int,
    val acoustic: Int,
    val cluster_id: Int
)

@Dao
interface SpotifyTrackDao {
    // Find track by exact title or similar title
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' LIMIT 1")
    suspend fun findTrack(query: String): SpotifyTrack?
    
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
}

@Database(entities = [SpotifyTrack::class], version = 3, exportSchema = false)
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
