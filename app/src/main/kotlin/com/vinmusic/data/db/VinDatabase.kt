package com.vinmusic.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "liked_songs")
data class LikedSong(
    @PrimaryKey val videoId: String,
    val title:       String,
    val author:      String,
    val durationText: String,
    val likedAt:     Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val videoId: String,
    val title:   String,
    val author:  String,
    val genre: String? = null,
    val durationText: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId:   String,
    val title:       String,
    val author:      String,
    val durationText: String,
    val filePath:    String,
    val sizeBytes:   Long   = 0L,
    val downloadedAt: Long  = System.currentTimeMillis(),
    val status:      String = "completed", // queued / downloading / completed / failed
    val progress:    Int    = 0,
    val thumbnailPath: String? = null,  // Local path to cached thumbnail
    val thumbnailUrl: String? = null    // Original YouTube thumbnail URL
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name:      String,
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned:  Boolean = false
)

@Entity(tableName = "playlist_songs",
    primaryKeys = ["playlistId", "videoId"])
data class PlaylistSongEntity(
    val playlistId: Long,
    val videoId:    String,
    val title:      String,
    val author:     String,
    val durationText: String,
    val position:   Int = 0
)

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val position: Int,
    val videoId:      String,
    val title:        String,
    val author:       String,
    val durationText: String
)

@Entity(tableName = "interaction_signals")
data class InteractionSignal(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    var playCount: Int = 0,
    var skipCount: Int = 0,
    var completeCount: Int = 0,
    var repeatCount: Int = 0,
    var lastPlayedAt: Long = 0,
    var isLiked: Boolean = false,
    var isDownloaded: Boolean = false,
    var searchClickCount: Int = 0,
    var skip20sCount: Int = 0
)

@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val videoId: String,
    val lyricsType: String, // "synced", "plain", "not_found"
    val content: String // JSON for synced, raw text for plain, empty for not_found
)

@Entity(tableName = "user_accounts")
data class UserAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gender: String,
    val dob: String,
    val email: String,
    val phone: String
)

/** Metrolist-style related song cache (seed → related tracks from YouTube Music). */
@Entity(tableName = "related_song_map", primaryKeys = ["songId", "relatedVideoId"])
data class RelatedSongMap(
    val songId: String,
    val relatedVideoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    val savedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "song_cache_meta")
data class SongCacheMeta(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val totalPlayTime: Long = 0L,
)

@Entity(tableName = "followed_artists")
data class FollowedArtist(
    @PrimaryKey val channelId: String,
    val name: String,
    val thumbnail: String,
    val subscriberCount: String = "",
    val followedAt: Long = System.currentTimeMillis()
)


// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface LikedSongDao {
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun getAllFlow(): Flow<List<LikedSong>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getAll(): List<LikedSong>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: LikedSong)

    @Query("DELETE FROM liked_songs WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :id)")
    suspend fun isLiked(id: String): Boolean
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 50")
    fun getRecentFlow(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY playedAt DESC")
    suspend fun getAllHistory(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllFlow(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dl: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM downloads WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT filePath FROM downloads WHERE videoId = :id")
    suspend fun getFilePath(id: String): String?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY downloadedAt ASC")
    suspend fun getByStatus(status: String): List<DownloadEntity>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, createdAt DESC")
    fun getAllFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, createdAt DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET isPinned = NOT isPinned WHERE id = :id")
    suspend fun togglePin(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    fun getSongsFlow(id: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs")
    suspend fun getAllPlaylistSongs(): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(s: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :pid AND videoId = :vid")
    suspend fun removeSong(pid: Long, vid: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY position")
    suspend fun getAll(): List<QueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueEntity>)

    @Query("DELETE FROM queue")
    suspend fun clear()
}

@Dao
interface InteractionSignalDao {
    @Query("SELECT * FROM interaction_signals")
    suspend fun getAll(): List<InteractionSignal>

    @Query("SELECT * FROM interaction_signals WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): InteractionSignal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signal: InteractionSignal)

    @Query("UPDATE interaction_signals SET isLiked = :liked WHERE videoId = :id")
    suspend fun updateLiked(id: String, liked: Boolean)

    @Query("UPDATE interaction_signals SET isDownloaded = :downloaded WHERE videoId = :id")
    suspend fun updateDownloaded(id: String, downloaded: Boolean)
}

@Dao
interface CachedLyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): CachedLyricsEntity?

    @Query("SELECT * FROM cached_lyrics")
    suspend fun getAll(): List<CachedLyricsEntity>

    @Query("SELECT * FROM cached_lyrics WHERE content != '' ORDER BY RANDOM() LIMIT 5")
    suspend fun getRandomLyrics(): List<CachedLyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lyrics: CachedLyricsEntity)

    @Query("DELETE FROM cached_lyrics WHERE videoId = :id")
    suspend fun delete(id: String)
}

@Dao
interface RelatedSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RelatedSongMap>)

    @Query("DELETE FROM related_song_map WHERE songId = :songId")
    suspend fun deleteForSong(songId: String)

    @Query(
        """
        SELECT relatedVideoId AS videoId, title, author, durationText
        FROM related_song_map
        WHERE songId IN (SELECT videoId FROM history ORDER BY playedAt DESC LIMIT 5)
           OR songId IN (SELECT videoId FROM interaction_signals ORDER BY lastPlayedAt DESC LIMIT 5)
           OR songId IN (SELECT videoId FROM song_cache_meta ORDER BY lastPlayedAt DESC LIMIT 5)
        GROUP BY relatedVideoId
        ORDER BY MAX(savedAt) DESC
        LIMIT :limit
        """
    )
    suspend fun quickPickVideos(limit: Int = 20): List<QuickPickRow>

    @Query(
        """
        SELECT relatedVideoId AS videoId, title, author, durationText
        FROM related_song_map
        WHERE songId = :songId
        ORDER BY savedAt DESC
        LIMIT :limit
        """
    )
    suspend fun relatedForSong(songId: String, limit: Int = 20): List<QuickPickRow>

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    suspend fun hasRelated(songId: String): Boolean
}

data class QuickPickRow(
    val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
)

@Dao
interface SongCacheMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SongCacheMeta)

    @Query("SELECT * FROM song_cache_meta ORDER BY totalPlayTime DESC LIMIT :limit")
    suspend fun topPlayed(limit: Int): List<SongCacheMeta>

    @Query(
        """
        SELECT * FROM song_cache_meta
        WHERE lastPlayedAt < :before
        ORDER BY totalPlayTime DESC
        LIMIT :limit
        """
    )
    suspend fun forgottenFavorites(before: Long, limit: Int): List<SongCacheMeta>
}

@Dao
interface UserAccountDao {
    @Query("SELECT * FROM user_accounts WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): UserAccount?

    @Query("SELECT * FROM user_accounts WHERE phone = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): UserAccount?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: UserAccount): Long
}

@Dao
interface FollowedArtistDao {
    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun getAllFlow(): Flow<List<FollowedArtist>>

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun getAll(): List<FollowedArtist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artist: FollowedArtist)

    @Query("DELETE FROM followed_artists WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    suspend fun isFollowing(channelId: String): Boolean
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities  = [LikedSong::class, HistoryEntry::class, DownloadEntity::class,
                 PlaylistEntity::class, PlaylistSongEntity::class, QueueEntity::class,
                 InteractionSignal::class, CachedLyricsEntity::class, UserAccount::class,
                 RelatedSongMap::class, SongCacheMeta::class, FollowedArtist::class],
    version   = 11,
    exportSchema = false
)
abstract class VinDatabase : RoomDatabase() {
    abstract fun likedSongDao(): LikedSongDao
    abstract fun historyDao():   HistoryDao
    abstract fun downloadDao():  DownloadDao
    abstract fun playlistDao():  PlaylistDao
    abstract fun queueDao():     QueueDao
    abstract fun interactionSignalDao(): InteractionSignalDao
    abstract fun cachedLyricsDao(): CachedLyricsDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun relatedSongDao(): RelatedSongDao
    abstract fun songCacheMetaDao(): SongCacheMetaDao
    abstract fun followedArtistDao(): FollowedArtistDao

    companion object {
        @Volatile private var INSTANCE: VinDatabase? = null

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create followed_artists table if it doesn't exist
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `followed_artists` (
                        `channelId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `subscriberCount` TEXT NOT NULL DEFAULT '',
                        `followedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`channelId`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(ctx: Context): VinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(ctx, VinDatabase::class.java, "vin_music.db")
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}

